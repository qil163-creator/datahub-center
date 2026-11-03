package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlVersionRestoreReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlVersionCompareRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlVersionMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlVersionServiceTest {
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }
    private final EtlVersionService service = new EtlVersionService();
    private final EtlVersionMapper versions = mock(EtlVersionMapper.class);
    private final EtlPipelineMapper pipelines = mock(EtlPipelineMapper.class);
    private final AtomicReference<LambdaUpdateWrapper<EtlPipelineDO>> update = new AtomicReference<>();
    private EtlVersionDO historical;
    private EtlPipelineDO current;
    private static final String NODES = "[{\"id\":\"node\",\"x\":10,\"y\":20,\"properties\":{\"name\":\"input\",\"toolCode\":\"generate-records\",\"toolType\":1,\"nodeJson\":\"{\\\"count\\\":1}\"}}]";
    private static final String SNAPSHOT = "{\"nodes\":" + NODES + ",\"edges\":[],\"parameters\":[],\"taskType\":1}";

    @BeforeEach void setup() {
        var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
        var assistant = new MapperBuilderAssistant(config, "etl-version-test");
        TableInfoHelper.initTableInfo(assistant, EtlVersionDO.class); TableInfoHelper.initTableInfo(assistant, EtlPipelineDO.class);
        ReflectionTestUtils.setField(service, "versions", versions); ReflectionTestUtils.setField(service, "pipelines", pipelines);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(55L).setTenantId(7L), new MockHttpServletRequest());
        historical = new EtlVersionDO(); historical.setId(100L); historical.setPipelineId(10L); historical.setRevision(2);
        historical.setEventType("PUBLISH"); historical.setConfigJson(SNAPSHOT); historical.setContentHash(DigestUtil.sha256Hex(SNAPSHOT));
        current = EtlPipelineDO.builder().id(10L).revision(4).nodesJson(NODES).edgesJson("[]").paramsJson("[]")
                .taskType(1).publishedConfigJson("published-current").publishedRevision(3).build();
        when(versions.selectById(100L)).thenReturn(historical);
        when(pipelines.selectById(10L)).thenReturn(current); when(pipelines.selectOne(any())).thenReturn(current);
        when(versions.insert(any(EtlVersionDO.class))).thenReturn(1);
        when(pipelines.update(isNull(), any())).thenAnswer(call -> { update.set(call.getArgument(1)); return 1; });
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void publicationCaptureRequiresActualEnclosingSpringTransaction() {
        DataSource source = mock(DataSource.class);
        try (var app = new AnnotationConfigApplicationContext()) {
            app.register(Transactions.class);
            app.getBeanFactory().registerSingleton("versions", versions); app.getBeanFactory().registerSingleton("pipelines", pipelines);
            app.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
            app.registerBean(EtlVersionService.class); app.refresh();
            assertThrows(IllegalTransactionStateException.class,
                    () -> app.getBean(EtlVersionService.class).capturePublished(current, 1, 55L, SNAPSHOT));
            verify(versions, never()).insert(any(EtlVersionDO.class)); verifyNoInteractions(source);
        }
    }

    @Test void restoreWritesOnlyNewDraftAndAppendOnlyHistory() {
        String originalHash = historical.getContentHash();
        assertEquals(5, service.restore(request()));
        var write = update.get(); String assignment = write.getSqlSet();
        assertTrue(assignment.contains("nodes_json")); assertTrue(assignment.contains("revision"));
        assertFalse(assignment.contains("published")); assertFalse(assignment.contains("schedule_generation")); assertFalse(assignment.contains("run_status"));
        assertTrue(write.getSqlSegment().contains("revision"));
        var captor = org.mockito.ArgumentCaptor.forClass(EtlVersionDO.class); verify(versions).insert(captor.capture());
        var appended = captor.getValue(); assertEquals("RESTORE", appended.getEventType()); assertEquals(5, appended.getRevision());
        assertEquals(100L, appended.getSourceVersionId()); assertEquals(55L, appended.getActorId()); assertEquals("restore reason", appended.getReason());
        assertEquals(SNAPSHOT, historical.getConfigJson()); assertEquals(originalHash, historical.getContentHash()); assertEquals(2, historical.getRevision());
        assertEquals("published-current", current.getPublishedConfigJson()); assertEquals(3, current.getPublishedRevision());
        verify(versions, never()).update(any(), any()); verify(versions, never()).deleteById(anyLong());
    }

    @Test void staleRevisionOrLostCasNeverCreatesRestoreHistory() {
        var stale = request(); stale.setExpectedRevision(3);
        assertThrows(RuntimeException.class, () -> service.restore(stale)); verify(pipelines, never()).update(any(), any());
        doReturn(0).when(pipelines).update(isNull(), any());
        assertThrows(RuntimeException.class, () -> service.restore(request())); verify(versions, never()).insert(any(EtlVersionDO.class));
    }

    @Test void tamperedSnapshotIsRejectedForDetailsDiffAndRestore() {
        historical.setConfigJson(SNAPSHOT.replace("count", "tampered"));
        assertThrows(RuntimeException.class, () -> service.get(100L));
        assertThrows(RuntimeException.class, () -> service.diff(100L));
        assertThrows(RuntimeException.class, () -> service.restore(request()));
        verify(pipelines, never()).update(any(), any()); verify(versions, never()).insert(any(EtlVersionDO.class));
    }

    @Test void semanticDiffIgnoresPositionRuntimeStylingAndStringObjectConfigRepresentation() {
        current.setNodesJson("[{\"id\":\"node\",\"x\":999,\"y\":888,\"selected\":true,\"properties\":{\"name\":\"input\",\"toolCode\":\"generate-records\",\"toolType\":1,\"nodeJson\":{\"count\":1},\"runStatus\":\"SUCCESS\",\"highlight\":true}}]");
        assertTrue(service.diff(100L).isEmpty(), service.diff(100L).toString());
        current.setNodesJson(current.getNodesJson().replace("\"count\":1", "\"count\":2"));
        assertEquals(List.of(new EtlVersionService.Change("NODE_CHANGED", "node")), service.diff(100L));
        assertFalse(JsonUtils.toJsonString(service.diff(100L)).contains("count"));
    }

    @Test void missingActorAndNonPublishedHistoryCannotRestore() {
        SecurityContextHolder.clearContext(); assertThrows(RuntimeException.class, () -> service.restore(request()));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(55L), new MockHttpServletRequest());
        historical.setEventType("RESTORE"); assertThrows(RuntimeException.class, () -> service.restore(request()));
        verify(pipelines, never()).update(any(), any()); verify(versions, never()).insert(any(EtlVersionDO.class));
    }

    @Test void edgeDiffRetainsEveryPortAndDuplicateMultiplicity() {
        String pass = "{\"sourceNodeId\":\"node\",\"targetNodeId\":\"sink\",\"sourcePort\":\"pass\"}";
        String reject = pass.replace("pass", "reject");
        String edges = "[" + pass + "," + reject + "]";
        String snapshot = SNAPSHOT.replace("\"edges\":[]", "\"edges\":" + edges);
        historical.setConfigJson(snapshot); historical.setContentHash(DigestUtil.sha256Hex(snapshot));
        current.setEdgesJson("[" + reject + "]");
        assertEquals(List.of(new EtlVersionService.Change("EDGES_CHANGED", "edges")), service.diff(100L));
        current.setEdgesJson(edges); assertTrue(service.diff(100L).isEmpty());
        current.setEdgesJson("[" + pass + "," + reject + "," + reject + "]");
        assertEquals(List.of(new EtlVersionService.Change("EDGES_CHANGED", "edges")), service.diff(100L));
    }

    @Test void immutableCompareClassifiesChangesWithoutReturningConfigurationValues() {
        String sourceJson = "{\"nodes\":["
                + "{\"id\":\"call-1\",\"properties\":{\"name\":\"child\",\"toolCode\":\"sub-pipeline\",\"toolType\":4,\"nodeJson\":{\"pipelineId\":10,\"versionId\":2,\"token\":\"OLD_SECRET\"}}},"
                + "{\"id\":\"tool-node\",\"properties\":{\"name\":\"generator\",\"toolCode\":\"generate-records\",\"toolType\":1,\"nodeJson\":{\"count\":1}}}],"
                + "\"edges\":[],\"parameters\":[],\"taskType\":1}";
        historical.setConfigJson(sourceJson);
        historical.setContentHash(DigestUtil.sha256Hex(sourceJson));
        String targetJson = "{\"nodes\":["
                + "{\"id\":\"call-1\",\"x\":900,\"y\":800,\"properties\":{\"name\":\"child\",\"toolCode\":\"sub-pipeline\",\"toolType\":4,\"nodeJson\":{\"pipelineId\":10,\"versionId\":3,\"token\":\"TOP_SECRET\"}}},"
                + "{\"id\":\"tool-node\",\"properties\":{\"name\":\"generator\",\"toolCode\":\"column-map\",\"toolType\":1,\"nodeJson\":{\"mapping\":[\"value\"]}}},"
                + "{\"id\":\"sink\",\"properties\":{\"name\":\"sink\",\"toolCode\":\"write-csv\",\"toolType\":3,\"nodeJson\":{}}}],"
                + "\"edges\":[{\"sourceNodeId\":\"call-1\",\"targetNodeId\":\"sink\",\"sourcePort\":\"out\"}],"
                + "\"parameters\":[{\"name\":\"token\",\"defaultValue\":\"PARAM_SECRET\"}],\"qualityTaskId\":8,\"environmentId\":9,\"taskType\":2}";
        var target = version(200L, 10L, 3, "PUBLISH", targetJson);
        when(versions.selectById(200L)).thenReturn(target);

        var compared = service.compare(100L, 200L);

        assertEquals(100L, compared.getSourceVersionId());
        assertEquals(200L, compared.getTargetVersionId());
        assertTrue(compared.getChanges().stream().anyMatch(change -> change.type().equals("NODE_ADDED") && change.objectId().equals("sink")));
        assertTrue(compared.getChanges().stream().anyMatch(change -> change.type().equals("NODE_TOOL_CHANGED")));
        assertTrue(compared.getChanges().stream().anyMatch(change -> change.type().equals("SUBFLOW_VERSION_CHANGED")));
        assertTrue(compared.getChanges().stream().anyMatch(change -> change.type().equals("EDGE_ADDED")));
        assertTrue(compared.getChanges().stream().anyMatch(change -> change.type().equals("PARAMETERS_CHANGED")));
        String responseJson = JsonUtils.toJsonString(compared);
        assertFalse(responseJson.contains("TOP_SECRET"));
        assertFalse(responseJson.contains("PARAM_SECRET"));
        assertFalse(responseJson.contains("nodeJson"));
    }

    @Test void immutableCompareIgnoresNodeAndEdgeArrayOrderCoordinatesAndOutMainAlias() {
        String sourceJson = "{\"nodes\":["
                + "{\"id\":\"node\",\"x\":1,\"y\":2,\"properties\":{\"name\":\"input\",\"toolCode\":\"generate-records\",\"toolType\":1,\"nodeJson\":\"{\\\"count\\\":1,\\\"value\\\":2}\"}},"
                + "{\"id\":\"sink\",\"properties\":{\"name\":\"sink\",\"toolCode\":\"write-csv\",\"toolType\":3,\"nodeJson\":{}}}],"
                + "\"edges\":[{\"sourceNodeId\":\"node\",\"targetNodeId\":\"sink\",\"sourcePort\":\"out\"}],\"parameters\":[]}";
        String targetJson = "{\"nodes\":["
                + "{\"id\":\"sink\",\"x\":500,\"y\":600,\"properties\":{\"nodeJson\":{},\"toolType\":3,\"toolCode\":\"write-csv\",\"name\":\"sink\"}},"
                + "{\"id\":\"node\",\"x\":900,\"y\":800,\"properties\":{\"nodeJson\":{\"value\":2,\"count\":1},\"toolType\":1,\"toolCode\":\"generate-records\",\"name\":\"input\"}}],"
                + "\"edges\":[{\"sourceNodeId\":\"node\",\"targetNodeId\":\"sink\",\"properties\":{\"outputPort\":\"main\"}}],\"parameters\":[]}";
        historical.setConfigJson(sourceJson); historical.setContentHash(DigestUtil.sha256Hex(sourceJson));
        var target = version(200L, 10L, 3, "RESTORE", targetJson);
        when(versions.selectById(200L)).thenReturn(target);

        assertTrue(service.compare(100L, 200L).getChanges().isEmpty());
    }

    @Test void immutableCompareTreatsLegacyMissingParametersAsEmptyButRejectsWrongType() {
        String legacy = "{\"nodes\":" + NODES + ",\"edges\":[],\"taskType\":1}";
        historical.setConfigJson(legacy); historical.setContentHash(DigestUtil.sha256Hex(legacy));
        var equivalent = version(200L, 10L, 3, "PUBLISH",
                "{\"nodes\":" + NODES + ",\"edges\":[],\"parameters\":[],\"taskType\":1}");
        when(versions.selectById(200L)).thenReturn(equivalent);
        assertTrue(service.compare(100L, 200L).getChanges().isEmpty());

        var malformed = version(200L, 10L, 3, "PUBLISH",
                "{\"nodes\":" + NODES + ",\"edges\":[],\"parameters\":{},\"taskType\":1}");
        when(versions.selectById(200L)).thenReturn(malformed);
        assertThrows(RuntimeException.class, () -> service.compare(100L, 200L));
    }

    @Test void immutableCompareRejectsCrossPipelineTamperedAndOversizedSnapshots() {
        var otherPipeline = version(200L, 11L, 3, "PUBLISH", SNAPSHOT);
        when(versions.selectById(200L)).thenReturn(otherPipeline);
        assertThrows(RuntimeException.class, () -> service.compare(100L, 200L));

        otherPipeline.setPipelineId(10L);
        otherPipeline.setContentHash("wrong-hash");
        assertThrows(RuntimeException.class, () -> service.compare(100L, 200L));

        String oversizedNodes = "[" + String.join(",", java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> "{\"id\":\"n" + index + "\",\"properties\":{\"toolCode\":\"generate-records\",\"nodeJson\":{}}}")
                .toList()) + "]";
        String oversized = "{\"nodes\":" + oversizedNodes + ",\"edges\":[],\"parameters\":[]}";
        otherPipeline.setConfigJson(oversized);
        otherPipeline.setContentHash(DigestUtil.sha256Hex(oversized));
        assertThrows(RuntimeException.class, () -> service.compare(100L, 200L));
    }

    @Test void sharedCompareAndDraftDiffNormalizeOrdinaryInputAliasesAndDefaultDataKinds() {
        String nodes = "[{\"id\":\"node\",\"properties\":{\"toolCode\":\"generate-records\",\"toolType\":1,\"nodeJson\":{}}},"
                + "{\"id\":\"sink\",\"properties\":{\"toolCode\":\"sort-records\",\"toolType\":2,\"nodeJson\":{}}}]";
        for (String targetEdge : List.of(
                "{\"sourceNodeId\":\"node\",\"targetNodeId\":\"sink\",\"sourcePort\":\"main\",\"targetPort\":\"main\",\"properties\":{\"edgeType\":\"DATA\"}}",
                "{\"sourceNodeId\":\"node\",\"targetNodeId\":\"sink\",\"sourcePort\":\"\",\"targetPort\":\"\",\"properties\":{\"edgeType\":\"data\",\"outputPort\":\"main\"}}")) {
            compareSnapshots(nodes, nodes,
                    "[{\"sourceNodeId\":\"node\",\"targetNodeId\":\"sink\"}]", "[" + targetEdge + "]");
            assertTrue(service.compare(100L, 200L).getChanges().isEmpty());
            assertTrue(service.diff(100L).isEmpty());
        }
    }

    @Test void sharedDiffUsesRuntimeControlInferenceAndRetainsRowScriptDataMeaning() {
        String controlNodes = "[{\"id\":\"node\",\"properties\":{\"toolCode\":\"sql-script\",\"toolType\":4,\"nodeJson\":{}}},"
                + "{\"id\":\"sink\",\"properties\":{\"toolCode\":\"generate-records\",\"toolType\":1,\"nodeJson\":{}}}]";
        String oldEdges = "[{\"sourceNodeId\":\"node\",\"targetNodeId\":\"sink\"}]";
        String controlEdges = "[{\"sourceNodeId\":\"node\",\"targetNodeId\":\"sink\",\"properties\":{\"edgeType\":\"control\"}}]";
        compareSnapshots(controlNodes, controlNodes, oldEdges, controlEdges);
        assertTrue(service.compare(100L, 200L).getChanges().isEmpty()); assertTrue(service.diff(100L).isEmpty());

        String rowNodes = "[{\"id\":\"node\",\"properties\":{\"toolCode\":\"java-script\",\"toolType\":4,\"nodeJson\":{}}},"
                + "{\"id\":\"sink\",\"properties\":{\"toolCode\":\"sort-records\",\"toolType\":2,\"nodeJson\":{}}}]";
        compareSnapshots(rowNodes, rowNodes, oldEdges, controlEdges);
        assertEquals(1, service.compare(100L, 200L).getChangeCounts().get("EDGE_ADDED"));
        assertEquals(1, service.compare(100L, 200L).getChangeCounts().get("EDGE_REMOVED"));
        assertEquals(List.of(new EtlVersionService.Change("EDGES_CHANGED", "edges")), service.diff(100L));

    }

    @Test void namedChildPortsMainAndInRemainDistinctInCompareAndLegacyDiff() {
        String nodes = "[{\"id\":\"node\",\"properties\":{\"toolCode\":\"generate-records\",\"toolType\":1,\"nodeJson\":{}}},"
                + "{\"id\":\"call\",\"properties\":{\"toolCode\":\"sub-pipeline\",\"toolType\":2,\"nodeJson\":{\"pipelineId\":7,\"versionId\":9}}}]";
        String input = "[{\"sourceNodeId\":\"node\",\"targetNodeId\":\"call\",\"targetPort\":\"in\"}]";
        compareSnapshots(nodes, nodes, input, input.replace("\"in\"", "\"main\""));
        assertEquals(1, service.compare(100L, 200L).getChangeCounts().get("EDGE_ADDED"));
        assertEquals(1, service.compare(100L, 200L).getChangeCounts().get("EDGE_REMOVED"));
        assertEquals(List.of(new EtlVersionService.Change("EDGES_CHANGED", "edges")), service.diff(100L));

        compareSnapshots(nodes, nodes, input, "[{\"sourceNodeId\":\"node\",\"targetNodeId\":\"call\"}]");
        assertEquals(1, service.compare(100L, 200L).getChangeCounts().get("EDGE_ADDED"));
        assertEquals(1, service.compare(100L, 200L).getChangeCounts().get("EDGE_REMOVED"));
        assertEquals(List.of(new EtlVersionService.Change("EDGES_CHANGED", "edges")), service.diff(100L));
    }

    @Test void numericAndStringChildIdsAreEquivalentWithoutCollapsingUnsafeIdsOrBusinessValues() {
        String numeric = "[{\"id\":\"call\",\"properties\":{\"toolCode\":\"sub-pipeline\",\"toolType\":2,"
                + "\"nodeJson\":{\"pipelineId\":7,\"versionId\":9007199254740993,\"parameters\":{\"amount\":7}}}}]";
        String strings = numeric.replace("\"pipelineId\":7", "\"pipelineId\":\"7\"")
                .replace("\"versionId\":9007199254740993", "\"versionId\":\"9007199254740993\"");
        compareSnapshots(numeric, strings, "[]", "[]");
        assertTrue(service.compare(100L, 200L).getChanges().isEmpty()); assertTrue(service.diff(100L).isEmpty());

        compareSnapshots(numeric, strings.replace("9007199254740993", "9007199254740994"), "[]", "[]");
        assertEquals(List.of(new EtlVersionCompareRespVO.Change("SUBFLOW_VERSION_CHANGED", "call", 1)),
                service.compare(100L, 200L).getChanges());

        compareSnapshots(numeric, strings.replace("\"amount\":7", "\"amount\":\"7\""), "[]", "[]");
        assertEquals(List.of(new EtlVersionCompareRespVO.Change("NODE_CONFIG_CHANGED", "call", 1)),
                service.compare(100L, 200L).getChanges());
    }

    private void compareSnapshots(String oldNodes, String newNodes, String oldEdges, String newEdges) {
        String oldJson = "{\"nodes\":" + oldNodes + ",\"edges\":" + oldEdges + ",\"parameters\":[],\"taskType\":1}";
        String newJson = "{\"nodes\":" + newNodes + ",\"edges\":" + newEdges + ",\"parameters\":[],\"taskType\":1}";
        historical.setConfigJson(oldJson); historical.setContentHash(DigestUtil.sha256Hex(oldJson));
        when(versions.selectById(200L)).thenReturn(version(200L, 10L, 3, "PUBLISH", newJson));
        current.setNodesJson(newNodes); current.setEdgesJson(newEdges);
    }

    private static EtlVersionDO version(Long id, Long pipelineId, Integer revision, String eventType, String json) {
        var version = new EtlVersionDO();
        version.setId(id);
        version.setPipelineId(pipelineId);
        version.setRevision(revision);
        version.setEventType(eventType);
        version.setConfigJson(json);
        version.setContentHash(DigestUtil.sha256Hex(json));
        return version;
    }

    private static EtlVersionRestoreReqVO request() {
        var req = new EtlVersionRestoreReqVO(); req.setVersionId(100L); req.setExpectedRevision(4); req.setReason(" restore reason "); return req;
    }
}
