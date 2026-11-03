package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 保存、编辑回显和冻结均保留原生作业；不把连接器配置转换为平台单表模型。 */
class NativeSeatunnelSubmissionTest {
    @ParameterizedTest
    @CsvSource({"json,1", "json,2", "json,3", "hocon,1", "hocon,2", "hocon,3"})
    void supportsOriginalFormatsAndAllThreeScheduleTypes(String format, int taskType) {
        var service = new DataAccessServiceImpl();
        var request = request(format, taskType);
        request.setSourceDatabaseId(999L); request.setTargetDatabaseId(998L);
        DataAccessDO saved = ReflectionTestUtils.invokeMethod(service, "buildDataAccess", request);
        assertNotNull(saved);
        assertEquals(request.getSeatunnelConfig(), saved.getSeatunnelConfig());
        assertEquals(format, saved.getConfigFormat());
        assertEquals(taskType, saved.getTaskType());
        assertEquals(taskType == 3 ? request.getCron() : null, saved.getCron());
        assertNull(saved.getSourceDatabaseId()); assertNull(saved.getTargetDatabaseId());
        List<DataAccessRespVO> displayed = ReflectionTestUtils.invokeMethod(service, "convertToRespList", List.of(saved));
        assertNotNull(displayed);
        assertEquals(request.getSeatunnelConfig(), displayed.getFirst().getSeatunnelConfig());
        assertEquals(format, displayed.getFirst().getConfigFormat());
        var frozen = AccessRunSnapshot.restore(AccessRunSnapshot.freeze(saved));
        assertEquals(request.getSeatunnelConfig(), frozen.getSeatunnelConfig());
        assertEquals(format, frozen.getConfigFormat());
    }

    @Test void invalidJsonIsRejectedBeforeSaveWithoutLeakingInput() {
        var request = request("json", 2);
        request.setSeatunnelConfig("{invalid-private-fixture");
        var failure = assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(new DataAccessServiceImpl(), "buildDataAccess", request));
        assertFalse(failure.getMessage().contains("invalid-private-fixture"));
        assertNull(failure.getCause());
    }

    @Test void periodicNativeJobStillRequiresValidCron() {
        var request = request("hocon", 3);
        request.setCron(null);
        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(new DataAccessServiceImpl(), "buildDataAccess", request));
    }

    @Test void unsupportedFormatAndScheduleAreRejected() {
        var request = request("yaml", 2);
        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(new DataAccessServiceImpl(), "buildDataAccess", request));
        request.setConfigFormat("json"); request.setSeatunnelConfig("{}"); request.setTaskType(99);
        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(new DataAccessServiceImpl(), "buildDataAccess", request));
    }

    private DataAccessSaveReqVO request(String format, int taskType) {
        var request = new DataAccessSaveReqVO();
        request.setTaskName("原生作业"); request.setEngineType(2); request.setTaskType(taskType);
        request.setCron("0 0 * * * *"); request.setConfigFormat(format);
        request.setSeatunnelConfig(format.equals("json")
                ? "{\"env\": {\"parallelism\": 2}, \"source\": [{\"plugin_name\":\"Kafka\"}], \"transform\": [{\"plugin_name\":\"Sql\",\"query\":\"select * from events\"}], \"sink\":[{\"plugin_name\":\"Console\"}]}"
                : "# 原生 HOCON\nenv { parallelism = 2 }\nsource { Kafka {} }\ntransform { Sql { query = \"select * from events\" } }\nsink { Console {} }\n");
        return request;
    }
}
