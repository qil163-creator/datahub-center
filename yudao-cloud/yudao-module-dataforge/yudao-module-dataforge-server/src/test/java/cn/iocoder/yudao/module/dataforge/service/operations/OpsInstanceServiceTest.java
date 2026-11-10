package cn.iocoder.yudao.module.dataforge.service.operations;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer;
import cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.OpsInstanceController;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevDirMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.operations.OpsInstanceMapper;
import cn.iocoder.yudao.module.dataforge.service.dev.DevRecordService;
import cn.iocoder.yudao.module.dataforge.service.dev.DevRecordServiceImpl;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageRespVO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpsInstanceServiceTest {
    @Test void managedIntegrationDetailsExposeOnlyTheMatchingFrozenRuntimeIdentity() {
        var req = identity("INTEGRATION", "MANAGED", 500L);
        var row = new InstanceResp(); row.setId(500L); row.setTaskId(88L); row.setOrigin("MANAGED");
        when(mapper.selectInstance(req)).thenReturn(row);
        var runtime = new InstanceResp(); runtime.setDsProjectCode(9007199254740993L); runtime.setDsInstanceId(101);
        when(mapper.selectManagedIntegrationIdentity(500L, 88L)).thenReturn(runtime);
        var result = service.get(req);
        assertEquals(9007199254740993L, result.getDsProjectCode());
        assertEquals(101, result.getDsInstanceId());
        when(mapper.selectManagedIntegrationIdentity(500L, 88L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.get(req));
    }
    private OpsInstanceService service;
    private OpsInstanceMapper mapper;
    private DevRecordService developmentRecords;
    private EtlPipelineService etlPipelines;
    private IntegrationCategoryMapper categories;

    @BeforeEach void setup() {
        service = new OpsInstanceService(); mapper = mock(OpsInstanceMapper.class);
        categories = mock(IntegrationCategoryMapper.class);
        developmentRecords = mock(DevRecordService.class); etlPipelines = mock(EtlPipelineService.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "integrationCategories", categories);
        ReflectionTestUtils.setField(service, "developmentCategories", mock(DevDirMapper.class));
        ReflectionTestUtils.setField(service, "developmentRecords", developmentRecords);
        ReflectionTestUtils.setField(service, "etlPipelines", etlPipelines);
    }

    @Test void expandsDescendantsAndKeepsCategoryOrigin() {
        var root = new IntegrationCategoryDO(); root.setId(1L); root.setParentId(0L); root.setName("同步");
        var child = new IntegrationCategoryDO(); child.setId(6L); child.setParentId(1L); child.setName("原始数据");
        var other = new IntegrationCategoryDO(); other.setId(2L); other.setParentId(0L); other.setName("其他");
        when(categories.selectList()).thenReturn(List.of(root, child, other));
        var req = new PageReq(); req.setKind("INTEGRATION"); req.setCategoryId("source:1");
        when(mapper.countInstances(eq(req), eq(List.of(1L, 6L)), eq("source"))).thenReturn(3L);
        when(mapper.selectInstances(eq(req), eq(List.of(1L, 6L)), eq("source"), eq(0L))).thenReturn(List.of());
        assertEquals(3L, service.page(req).getTotal());
        verify(mapper).selectInstances(req, List.of(1L, 6L), "source", 0);
    }

    @Test void unknownCategoryDoesNotFallBackToAllInstances() {
        when(categories.selectList()).thenReturn(List.of());
        var req = new PageReq(); req.setKind("INTEGRATION"); req.setCategoryId("source:1");
        assertEquals(0L, service.page(req).getTotal()); verifyNoInteractions(mapper);
    }

    @Test void statisticsUsesTheSameDescendantScopeAndTaskMode() {
        var root = new IntegrationCategoryDO(); root.setId(1L); root.setParentId(0L); root.setName("同步");
        var child = new IntegrationCategoryDO(); child.setId(6L); child.setParentId(1L); child.setName("原始数据");
        when(categories.selectList()).thenReturn(List.of(root, child));
        var req = new StatisticsReq(); req.setKind("INTEGRATION"); req.setCategoryId("source:1"); req.setTaskType("OFFLINE");
        var stats = new StatisticsResp(); stats.setRunning(2L);
        when(mapper.selectStatistics(eq("INTEGRATION"), any(), any(), eq(List.of(1L, 6L)), eq("source"), eq("OFFLINE"))).thenReturn(stats);
        assertEquals(2L, service.statistics(req).getRunning());
        req.setCategoryId("source:999"); assertEquals(0L, service.statistics(req).getRunning());
        verify(mapper, times(1)).selectStatistics(any(), any(), any(), any(), any(), any());
    }

    @Test void keepsDataAccessAndFullSyncAsSeparateOperationsKinds() {
        var access = new PageReq(); access.setKind("DATA_ACCESS");
        when(mapper.countInstances(eq(access), isNull(), isNull())).thenReturn(2L);
        when(mapper.selectInstances(eq(access), isNull(), isNull(), eq(0L))).thenReturn(List.of());
        assertEquals(2L, service.page(access).getTotal());

        var fullSync = new PageReq(); fullSync.setKind("FULL_SYNC");
        when(mapper.countInstances(eq(fullSync), isNull(), isNull())).thenReturn(1L);
        when(mapper.selectInstances(eq(fullSync), isNull(), isNull(), eq(0L))).thenReturn(List.of());
        assertEquals(1L, service.page(fullSync).getTotal());
        verify(mapper).countInstances(access, null, null);
        verify(mapper).countInstances(fullSync, null, null);
    }

    @Test void accessOperationsReadTheAccessTaskLogAndMaskSecrets() {
        var req = identity("FULL_SYNC", "PLATFORM", 901L);
        when(mapper.selectInstance(req)).thenReturn(new InstanceResp());
        when(mapper.selectAccessLog(901L)).thenReturn("完成\npassword=fixture-secret\nrows=12");
        var response = service.log(req);
        assertFalse(response.getImportedSnapshot());
        assertTrue(response.getMessage().contains("完成"));
        assertTrue(response.getMessage().contains("rows=12"));
        assertFalse(response.getMessage().contains("fixture-secret"));
        verify(mapper).selectAccessLog(901L);
        verifyNoInteractions(developmentRecords, etlPipelines);
    }

    @Test void accessOperationsRejectSourceOriginLogs() {
        var req = identity("DATA_ACCESS", "SOURCE", 902L);
        when(mapper.selectInstance(req)).thenReturn(new InstanceResp());
        assertThrows(ServiceException.class, () -> service.log(req));
        verify(mapper, never()).selectAccessLog(anyLong());
    }

    @Test void accessMapperUsesDedicatedFullSyncPredicate() {
        var fullSync = new PageReq(); fullSync.setKind("FULL_SYNC");
        var args = new HashMap<String, Object>(); args.put("req", fullSync); args.put("offset", 0L);
        String sql = OpsInstanceMapper.Sql.page(args);
        assertTrue(sql.contains("dh_dataforge_access_task"));
        assertTrue(sql.contains("t.task_type IN (2,3)"));
        assertTrue(sql.contains("'FULL_SYNC' kind"));
        var access = new PageReq(); access.setKind("DATA_ACCESS"); args.put("req", access);
        assertFalse(OpsInstanceMapper.Sql.page(args).contains("t.task_type IN (2,3)"));
    }

    @Test void taskFilterIsBoundAndAbsentRequestsRetainExistingQuery() {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.addMapper(OpsInstanceMapper.class);
        var req = new PageReq();
        var args = new HashMap<String, Object>();
        args.put("req", req); args.put("categoryIds", null); args.put("categoryOrigin", null); args.put("offset", 0L);
        for (var kind : List.of("JOB", "FULL_SYNC", "INTEGRATION", "DEVELOPMENT", "DATA_ACCESS")) {
            req.setKind(kind); req.setTaskId(null);
            for (var method : List.of("selectInstances", "countInstances")) {
                var statement = config.getMappedStatement(OpsInstanceMapper.class.getName() + "." + method);
                assertFalse(statement.getBoundSql(args).getSql().contains("AND task_id=?"));
                req.setTaskId(9007199254740993L);
                var bound = statement.getBoundSql(args);
                assertTrue(bound.getSql().contains("AND task_id=?"));
                assertTrue(bound.getParameterMappings().stream().anyMatch(item -> "req.taskId".equals(item.getProperty())));
                req.setTaskId(null);
            }
        }
        req.setTaskId(0L);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(req).stream().anyMatch(item -> "taskId".equals(item.getPropertyPath().toString())));
        }
    }

    @Test void sourceLogsNeverReadOrCallPlatformRuntime() {
        var req = identity("INTEGRATION", "SOURCE", 7L);
        when(mapper.selectInstance(req)).thenReturn(new InstanceResp());
        when(mapper.selectIntegrationLog(7L)).thenReturn("[2026-10-22 10:00:00] INFO 任务成功\npassword=fixture-secret\njdbc:fixture\n");
        var log = service.log(req); assertTrue(log.getImportedSnapshot());
        assertTrue(log.getMessage().contains("任务成功"));
        assertFalse(log.getMessage().contains("fixture-secret")); assertFalse(log.getMessage().contains("jdbc:"));
        verifyNoInteractions(developmentRecords, etlPipelines);
    }

    @Test void missingOriginIdentityCannotReadAnotherSourceLog() {
        var req = identity("INTEGRATION", "PLATFORM", 7L);
        assertThrows(ServiceException.class, () -> service.log(req));
        verifyNoInteractions(developmentRecords, etlPipelines);
        verify(mapper, never()).selectIntegrationLog(anyLong());
    }

    @Test void platformNativeGuardFailureNeverFallsBackToSourceLogs() {
        var req = identity("DEVELOPMENT", "PLATFORM", 865L);
        when(mapper.selectInstance(req)).thenReturn(new InstanceResp());
        var failure = new ServiceException(1500020001, "原生日志权限不足");
        when(developmentRecords.downloadTaskLog(865L)).thenThrow(failure);
        assertSame(failure, assertThrows(ServiceException.class, () -> service.log(req)));
        assertSame(failure, assertThrows(ServiceException.class, () -> service.download(req)));
        verify(mapper, never()).selectDevelopmentLog(anyLong());
        verify(mapper, never()).selectIntegrationLog(anyLong());
    }

    @Test void platformLogAggregationIncludesTheErrorAfterLineOneThousand() {
        var req = identity("DEVELOPMENT", "PLATFORM", 865L);
        when(mapper.selectInstance(req)).thenReturn(new InstanceResp());
        var original = spy(new DevRecordServiceImpl());
        doReturn(DevRecordDO.builder().id(865L).runKey("受控测试运行").build()).when(original).getRecord(865L);
        doAnswer(call -> {
            DevTaskLogPageReqVO page = call.getArgument(0);
            var response = new DevTaskLogPageRespVO(); response.setAvailable(true);
            response.setMayHaveMore(page.getSkipLineNum() == 0); response.setReadLines(page.getSkipLineNum() == 0 ? 1000 : 1);
            response.setNextSkipLineNum(page.getSkipLineNum() == 0 ? 1000 : 1001);
            response.setMessage(page.getSkipLineNum() == 0 ? "INFO 执行中\n".repeat(1000) : "ERROR 第1001行异常");
            return response;
        }).when(original).getTaskLog(any());
        ReflectionTestUtils.setField(service, "developmentRecords", original);
        assertTrue(service.log(req).getMessage().contains("ERROR 第1001行异常"));
        verify(original, times(2)).getTaskLog(any());
    }

    @Test void flowProjectionRemovesParametersAndNestedValues() {
        String graph = "{\"nodes\":[{\"id\":\"1\",\"label\":\"输入\",\"type\":\"1\",\"x\":2,\"parameters\":{\"private\":\"fixture\"}},{\"id\":{\"private\":\"fixture\"}}],\"edges\":[{\"source\":\"1\",\"target\":\"2\",\"config\":\"fixture\"}]}";
        String safe = OpsInstanceService.safeFlow(graph);
        assertFalse(safe.contains("fixture")); assertFalse(safe.contains("parameters"));
        assertTrue(safe.contains("输入")); assertTrue(safe.contains("source"));
        assertEquals("{\"nodes\":[],\"edges\":[]}", OpsInstanceService.safeFlow("invalid"));
    }

    @Test void logIsBoundedAfterMasking() {
        assertTrue(OpsInstanceService.safeLog("x".repeat(OpsInstanceService.MAX_LOG_CHARS + 1)).endsWith("[日志超出展示上限]"));
        assertEquals("", OpsInstanceService.safeLog(null));
    }

    @Test void rejectsInvalidDatesAndOverflowingCategoryIds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var req = new PageReq(); req.setKind("DEVELOPMENT");
            req.setStartDate(LocalDate.of(2026, 10, 23)); req.setEndDate(LocalDate.of(2026, 10, 22));
            req.setCategoryId("source:9999999999999999999");
            assertEquals(2, factory.getValidator().validate(req).size());
        }
    }

    @Test void serializesLosslessIdAndExplicitLocalDateTime() {
        var module = new SimpleModule(); module.addSerializer(Long.class, NumberSerializer.INSTANCE);
        module.addSerializer(LocalDateTime.class, TimestampLocalDateTimeSerializer.INSTANCE);
        var json = JsonMapper.builder().addModule(module).build();
        var row = new InstanceResp(); row.setId(9007199254740993L);
        row.setStartTime(LocalDateTime.of(2026, 10, 22, 19, 11, 2));
        var tree = json.readTree(json.writeValueAsString(row));
        assertEquals("9007199254740993", tree.get("id").asString()); assertTrue(tree.get("id").isString());
        assertEquals("2026-10-22 19:11:02", tree.get("startTime").asString());
    }

    @Test void everyEntryPointHasQueryOrExportPermission() {
        for (var method : OpsInstanceController.class.getDeclaredMethods()) {
            var permission = method.getAnnotation(PreAuthorize.class);
            assertNotNull(permission, method.getName());
            assertEquals("download".equals(method.getName()) ? "@ss.hasPermission('dataforge:ops-instance:export')"
                    : "@ss.hasPermission('dataforge:ops-instance:query')", permission.value());
        }
    }

    private static IdentityReq identity(String kind, String origin, Long id) {
        var req = new IdentityReq(); req.setKind(kind); req.setOrigin(origin); req.setId(id); return req;
    }
    @Test void persistedChildDatesRoundTripWithoutStringBecomingEpoch() {
        var time=LocalDateTime.of(2026,11,2,0,1,44);var child=new InstanceResp();child.setEndTime(time);
        var json=cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(child);
        assertEquals(time,cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(json,InstanceResp.class).getEndTime());
        var old=cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject("{\"startTime\":0,\"endTime\":1793548904000}",InstanceResp.class);
        assertNull(old.getStartTime());assertNotNull(old.getEndTime());
        var text=cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject("{\"endTime\":\"2026-11-02 00:01:44\"}",InstanceResp.class);
        assertEquals(time,text.getEndTime());
    }

}
