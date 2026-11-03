package cn.iocoder.yudao.module.dataforge.service.job;

import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.TaskOption;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 图编排验证覆盖引用、环、元数据与执行就绪状态，防止把可保存草稿当成可执行流程。 */
class JobServiceTest {
    @Test void taskPaletteDoesNotPerformExecutionPermissionRpc() {
        var service = new JobService();
        var mapper = mock(cn.iocoder.yudao.module.dataforge.dal.mysql.job.JobTaskMapper.class);
        var managed = mock(JobManagedRunService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "jobs", mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "runtime", new cn.iocoder.yudao.module.dataforge.service.integration.IntegrationRuntimeProperties());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "managedRuns", managed);
        var option = new TaskOption(); option.setId(1L); option.setKind("DEVELOPMENT");
        when(mapper.integrationOptions(any())).thenReturn(List.of());
        when(mapper.developmentOptions()).thenReturn(List.of(option));
        assertEquals(List.of(option), service.taskOptions());
        verifyNoInteractions(managed);
    }

    @Test void summaryCheckDoesNotCallRuntimeButExplicitCheckStillDoes() {
        var service = service(true);
        var runtime = (JobManagedRunService) org.springframework.test.util.ReflectionTestUtils.getField(service, "managedRuns");
        String graph = "{\"nodes\":[" + node("a") + "],\"edges\":[]}";
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "checkFlow", graph, false);
        verifyNoInteractions(runtime);
        service.checkFlow(graph);
        verify(runtime).validateForRead(graph);
    }
    private JobService service(boolean ready) {
        var service=spy(new JobService());org.springframework.test.util.ReflectionTestUtils.setField(service,"managedRuns",mock(JobManagedRunService.class));var option=new TaskOption();option.setId(9007199254740993L);option.setName("水情任务");option.setKind("INTEGRATION");option.setExecutable(ready);
        doReturn(List.of(option)).when(service).taskOptions();return service;
    }
    private static String node(String id) { return "{\"id\":\""+id+"\",\"kind\":\"INTEGRATION\",\"taskId\":\"9007199254740993\",\"name\":\"水情任务\",\"x\":100,\"y\":200}"; }
    @Test void allowsLosslessReferencesAndDisplayMetadata() {
        var json="{\"nodes\":["+node("a").replace("\"x\":100","\"status\":1,\"description\":\"描述\",\"taskType\":\"OFFLINE\",\"x\":100")+"],\"edges\":[]}";
        var result=service(true).checkFlow(json);assertTrue(result.isValid(),result.getErrors().toString());assertTrue(result.isExecutable(),result.getErrors().toString());assertTrue(result.getErrors().isEmpty());
    }
    @Test void rejectsCyclesAndDuplicateNodes() {
        var cycle="{\"nodes\":["+node("a")+","+node("b")+"],\"edges\":[{\"source\":\"a\",\"target\":\"b\"},{\"source\":\"b\",\"target\":\"a\"}]}";
        assertFalse(service(true).checkFlow(cycle).isValid());
        assertFalse(service(true).checkFlow("{\"nodes\":["+node("a")+","+node("a")+"],\"edges\":[]}").isValid());
    }
    @Test void validatesNodePriorityAndKeepsLegacyGraphsCompatible() {
        for (String priority : List.of("HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST")) {
            var json="{\"nodes\":["+node("a").replace("\"x\":100", "\"priority\":\""+priority+"\",\"x\":100")+"],\"edges\":[]}";
            assertTrue(service(true).checkFlow(json).isValid());
        }
        var invalid="{\"nodes\":["+node("a").replace("\"x\":100", "\"priority\":\"URGENT\",\"x\":100")+"],\"edges\":[]}";
        assertFalse(service(true).checkFlow(invalid).isValid());
        assertTrue(service(true).checkFlow("{\"nodes\":["+node("a")+"],\"edges\":[]}").isValid());
    }
    @Test void rejectsMissingReferencesAndCredentialFields() {
        assertFalse(service(true).checkFlow("{\"nodes\":["+node("a").replace("9007199254740993","9007199254740994")+"],\"edges\":[]}").isValid());
        assertFalse(service(true).checkFlow("{\"nodes\":["+node("a").replace("\"x\":100","\"password\":\"forbidden\",\"x\":100")+"],\"edges\":[]}").isValid());
        assertFalse(service(true).checkFlow("{\"nodes\":["+node("a")+"],\"edges\":[{\"source\":\"a\",\"target\":\"missing\"}]}").isValid());
    }
    @Test void separatesEditableDraftFromExecutionCapability() {
        var unavailable=service(false).checkFlow("{\"nodes\":["+node("a")+"],\"edges\":[]}");assertTrue(unavailable.isValid());assertFalse(unavailable.isExecutable());assertFalse(unavailable.getErrors().isEmpty());
        var empty=service(true).checkFlow("{\"nodes\":[],\"edges\":[]}");assertTrue(empty.isValid());assertFalse(empty.isExecutable());
    }
    @Test void rejectsReservedSchedulerRootNodeId() {
        var result=service(true).checkFlow("{\"nodes\":["+node("_root")+"],\"edges\":[]}");
        assertFalse(result.isValid());assertFalse(result.isExecutable());assertFalse(result.getErrors().isEmpty());
    }
    @Test void rejectsInvalidCoordinatesAndNonObjectGraphs() {
        assertFalse(service(true).checkFlow("{\"nodes\":["+node("a").replace("\"x\":100","\"x\":\"NaN\"")+"],\"edges\":[]}").isValid());
        assertFalse(service(true).checkFlow("invalid").isValid());
    }
}
