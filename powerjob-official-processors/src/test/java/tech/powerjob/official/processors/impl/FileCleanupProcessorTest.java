package tech.powerjob.official.processors.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.kfcfans.powerjob.worker.core.processor.TaskContext;
import org.junit.jupiter.api.Test;
import tech.powerjob.official.processors.TestUtils;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * description
 *
 * @author tjq
 * @since 2021/2/1
 */
class FileCleanupProcessorTest {

    @Test
    void testProcess() throws Exception {
        JSONObject params = new JSONObject();
        params.put("dirPath", "/Users/tjq/logs/oms-server");
        params.put("filePattern", "*");
        params.put("retentionTime", 10);
        JSONArray array = new JSONArray();
        array.add(params);

        String paramsStr = array.toJSONString();
        System.out.println(paramsStr);

        TaskContext taskContext = TestUtils.genTaskContext(paramsStr);
        System.out.println(new FileCleanupProcessor().process(taskContext));
    }

    @Test
    void testPatternCompile() throws Exception {
        String fileName = "abc.log";
        System.out.println(fileName.matches("[a-z]*\\.log"));
        System.out.println(Pattern.matches("\\*", fileName));
    }
}