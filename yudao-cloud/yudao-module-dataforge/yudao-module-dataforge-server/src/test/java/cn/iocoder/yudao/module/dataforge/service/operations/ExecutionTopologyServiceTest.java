package cn.iocoder.yudao.module.dataforge.service.operations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 执行拓扑映射契约测试。 */
class ExecutionTopologyServiceTest {

    private final ExecutionTopologyService service = new ExecutionTopologyService();

    @Test
    void shouldExposeAllQdataTaskKinds() {
        assertEquals(9, service.catalog().size());
        assertEquals("DBSWITCH", service.resolve("data_access_offline").getEngine());
        assertEquals("SEATUNNEL_REST", service.resolve("DATA_ACCESS_REALTIME").getScheduler());
        assertEquals("LOCAL_FILE", service.resolve("batch_file_import").getEngine());
        assertEquals("DOLPHIN_SCHEDULER", service.resolve("job_orchestration").getEngine());
    }

    @Test
    void shouldRejectUnknownTaskKindWithoutGuessingAnEngine() {
        assertNull(service.resolve("unknown"));
        assertNull(service.resolve(null));
    }
}
