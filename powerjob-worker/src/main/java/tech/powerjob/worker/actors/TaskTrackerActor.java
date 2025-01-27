package tech.powerjob.worker.actors;

import akka.actor.AbstractActor;
import akka.actor.Props;
import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tech.powerjob.common.enums.ExecuteType;
import tech.powerjob.common.enums.TimeExpressionType;
import tech.powerjob.common.model.InstanceDetail;
import tech.powerjob.common.request.ServerQueryInstanceStatusReq;
import tech.powerjob.common.request.ServerScheduleJobReq;
import tech.powerjob.common.request.ServerStopInstanceReq;
import tech.powerjob.common.response.AskResponse;
import tech.powerjob.worker.common.WorkerRuntime;
import tech.powerjob.worker.common.constants.TaskStatus;
import tech.powerjob.worker.core.tracker.manager.HeavyTaskTrackerManager;
import tech.powerjob.worker.core.tracker.manager.LightTaskTrackerManager;
import tech.powerjob.worker.core.tracker.task.TaskTracker;
import tech.powerjob.worker.core.tracker.task.heavy.HeavyTaskTracker;
import tech.powerjob.worker.core.tracker.task.light.LightTaskTracker;
import tech.powerjob.worker.persistence.TaskDO;
import tech.powerjob.worker.pojo.request.ProcessorMapTaskRequest;
import tech.powerjob.worker.pojo.request.ProcessorReportTaskStatusReq;
import tech.powerjob.worker.pojo.request.ProcessorTrackerStatusReportReq;

import java.util.List;

/**
 * worker 的 master 节点，处理来自 server 的 jobInstance 请求和来自 worker 的task 请求
 *
 * @author tjq
 * @since 2020/3/17
 */
@Slf4j
@AllArgsConstructor
public class TaskTrackerActor extends AbstractActor {

    private final WorkerRuntime workerRuntime;

    public static Props props(WorkerRuntime workerRuntime) {
        return Props.create(TaskTrackerActor.class, () -> new TaskTrackerActor(workerRuntime));
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ProcessorReportTaskStatusReq.class, this::onReceiveProcessorReportTaskStatusReq)
                .match(ServerScheduleJobReq.class, this::onReceiveServerScheduleJobReq)
                .match(ProcessorMapTaskRequest.class, this::onReceiveProcessorMapTaskRequest)
                .match(ProcessorTrackerStatusReportReq.class, this::onReceiveProcessorTrackerStatusReportReq)
                .match(ServerStopInstanceReq.class, this::onReceiveServerStopInstanceReq)
                .match(ServerQueryInstanceStatusReq.class, this::onReceiveServerQueryInstanceStatusReq)
                .matchAny(obj -> log.warn("[ServerRequestActor] receive unknown request: {}.", obj))
                .build();
    }


    /**
     * 子任务状态上报 处理器
     */
    private void onReceiveProcessorReportTaskStatusReq(ProcessorReportTaskStatusReq req) {

        int taskStatus = req.getStatus();
        // 只有重量级任务才会有两级任务状态上报的机制
        HeavyTaskTracker taskTracker = HeavyTaskTrackerManager.getTaskTracker(req.getInstanceId());
        // 结束状态需要回复接受成功
        if (TaskStatus.FINISHED_STATUS.contains(taskStatus)) {
            AskResponse askResponse = AskResponse.succeed(null);
            getSender().tell(askResponse, getSelf());
        }

        // 手动停止 TaskTracker 的情况下会出现这种情况
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive ProcessorReportTaskStatusReq({}) but system can't find TaskTracker.", req);
            return;
        }

        if (ProcessorReportTaskStatusReq.BROADCAST.equals(req.getCmd())) {
            taskTracker.broadcast(taskStatus == TaskStatus.WORKER_PROCESS_SUCCESS.getValue(), req.getSubInstanceId(), req.getTaskId(), req.getResult());
        }

        taskTracker.updateTaskStatus(req.getSubInstanceId(), req.getTaskId(), taskStatus, req.getReportTime(), req.getResult());

        // 更新工作流上下文
        taskTracker.updateAppendedWfContext(req.getAppendedWfContext());
    }

    /**
     * 子任务 map 处理器
     */
    private void onReceiveProcessorMapTaskRequest(ProcessorMapTaskRequest req) {

        HeavyTaskTracker taskTracker = HeavyTaskTrackerManager.getTaskTracker(req.getInstanceId());
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive ProcessorMapTaskRequest({}) but system can't find TaskTracker.", req);
            return;
        }

        boolean success = false;
        List<TaskDO> subTaskList = Lists.newLinkedList();

        try {

            req.getSubTasks().forEach(originSubTask -> {
                TaskDO subTask = new TaskDO();

                subTask.setTaskName(req.getTaskName());
                subTask.setSubInstanceId(req.getSubInstanceId());

                subTask.setTaskId(originSubTask.getTaskId());
                subTask.setTaskContent(originSubTask.getTaskContent());

                subTaskList.add(subTask);
            });

            success = taskTracker.submitTask(subTaskList);
        }catch (Exception e) {
            log.warn("[TaskTrackerActor] process map task(instanceId={}) failed.", req.getInstanceId(), e);
        }

        AskResponse response = new AskResponse();
        response.setSuccess(success);
        getSender().tell(response, getSelf());
    }

    /**
     * 服务器任务调度处理器
     */
    private void onReceiveServerScheduleJobReq(ServerScheduleJobReq req) {
        log.debug("[TaskTrackerActor] server schedule job by request: {}.", req);
        Long instanceId = req.getInstanceId();
        // 区分轻量级任务模型以及重量级任务模型
        if (isLightweightTask(req)) {
            final LightTaskTracker taskTracker = LightTaskTrackerManager.getTaskTracker(instanceId);
            if (taskTracker != null) {
                log.warn("[TaskTrackerActor] LightTaskTracker({}) for instance(id={}) already exists.", taskTracker, instanceId);
                return;
            }
            // 判断是否已经 overload
            if (LightTaskTrackerManager.currentTaskTrackerSize() >= workerRuntime.getWorkerConfig().getMaxLightweightTaskNum() * LightTaskTrackerManager.OVERLOAD_FACTOR) {
                // ignore this request
                log.warn("[TaskTrackerActor] this worker is overload,ignore this request(instanceId={}),current size = {}!",instanceId,LightTaskTrackerManager.currentTaskTrackerSize());
                return;
            }
            if (LightTaskTrackerManager.currentTaskTrackerSize() >= workerRuntime.getWorkerConfig().getMaxLightweightTaskNum()) {
                log.warn("[TaskTrackerActor] this worker will be overload soon,current size = {}!",LightTaskTrackerManager.currentTaskTrackerSize());
            }
            // 创建轻量级任务
            LightTaskTrackerManager.atomicCreateTaskTracker(instanceId, ignore -> LightTaskTracker.create(req, workerRuntime));
        } else {
            HeavyTaskTracker taskTracker = HeavyTaskTrackerManager.getTaskTracker(instanceId);
            if (taskTracker != null) {
                log.warn("[TaskTrackerActor] HeavyTaskTracker({}) for instance(id={}) already exists.", taskTracker, instanceId);
                return;
            }
            // 判断是否已经 overload
            if (HeavyTaskTrackerManager.currentTaskTrackerSize() >= workerRuntime.getWorkerConfig().getMaxHeavyweightTaskNum()) {
                // ignore this request
                log.warn("[TaskTrackerActor] this worker is overload,ignore this request(instanceId={})! current size = {},", instanceId, HeavyTaskTrackerManager.currentTaskTrackerSize());
                return;
            }
            // 原子创建，防止多实例的存在
            HeavyTaskTrackerManager.atomicCreateTaskTracker(instanceId, ignore -> HeavyTaskTracker.create(req, workerRuntime));
        }
    }

    /**
     * ProcessorTracker 心跳处理器
     */
    private void onReceiveProcessorTrackerStatusReportReq(ProcessorTrackerStatusReportReq req) {

        HeavyTaskTracker taskTracker = HeavyTaskTrackerManager.getTaskTracker(req.getInstanceId());
        if (taskTracker == null) {
            log.warn("[TaskTrackerActor] receive ProcessorTrackerStatusReportReq({}) but system can't find TaskTracker.", req);
            return;
        }
        taskTracker.receiveProcessorTrackerHeartbeat(req);
    }

    /**
     * 停止任务实例
     */
    private void onReceiveServerStopInstanceReq(ServerStopInstanceReq req) {


        log.info("[TaskTrackerActor] receive ServerStopInstanceReq({}).", req);
        HeavyTaskTracker heavyTaskTracker = HeavyTaskTrackerManager.getTaskTracker(req.getInstanceId());
        if (heavyTaskTracker != null) {
            heavyTaskTracker.stopTask();
            return;
        }
        LightTaskTracker lightTaskTracker = LightTaskTrackerManager.getTaskTracker(req.getInstanceId());
        if (lightTaskTracker != null) {
            lightTaskTracker.stopTask();
            return;
        }
        log.warn("[TaskTrackerActor] receive ServerStopInstanceReq({}) but system can't find TaskTracker.", req);
    }

    /**
     * 查询任务实例运行状态
     */
    private void onReceiveServerQueryInstanceStatusReq(ServerQueryInstanceStatusReq req) {
        AskResponse askResponse;
        TaskTracker taskTracker = HeavyTaskTrackerManager.getTaskTracker(req.getInstanceId());
        if (taskTracker == null && (taskTracker = LightTaskTrackerManager.getTaskTracker(req.getInstanceId())) == null) {
            log.warn("[TaskTrackerActor] receive ServerQueryInstanceStatusReq({}) but system can't find TaskTracker.", req);
            askResponse = AskResponse.failed("can't find TaskTracker");
        } else {
            InstanceDetail instanceDetail = taskTracker.fetchRunningStatus();
            askResponse = AskResponse.succeed(instanceDetail);
        }
        getSender().tell(askResponse, getSelf());
    }


    private boolean isLightweightTask(ServerScheduleJobReq serverScheduleJobReq) {
        final ExecuteType executeType = ExecuteType.valueOf(serverScheduleJobReq.getExecuteType());
        // 非单机执行的一定不是
        if (executeType != ExecuteType.STANDALONE){
            return false;
        }
        TimeExpressionType timeExpressionType = TimeExpressionType.valueOf(serverScheduleJobReq.getTimeExpressionType());
        // 固定频率以及固定延迟的也一定不是
        return timeExpressionType != TimeExpressionType.FIXED_DELAY && timeExpressionType != TimeExpressionType.FIXED_RATE;
    }
}
