package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MasterDataWorkflowTest {
    private MasterDataChangeService service;
    private MasterDataService data;
    private MasterCodeRuleService codes;
    private BpmProcessInstanceApi bpm;
    private MasterChangeLogMapper logs;
    private MasterModelDO model;
    private final Map<Long, MasterDataChangeDO> changes = new HashMap<>();
    private long nextId;

    static class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    @BeforeEach
    void setup() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                MasterDataChangeDO.class);
        TenantContextHolder.clear(); TenantContextHolder.setIgnore(false);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setTenantId(1L), new MockHttpServletRequest());
        service = new MasterDataChangeService();
        data = mock(MasterDataService.class); codes = mock(MasterCodeRuleService.class);
        bpm = mock(BpmProcessInstanceApi.class); logs = mock(MasterChangeLogMapper.class);
        var mapper = mock(MasterDataChangeMapper.class);
        var models = mock(MasterModelMapper.class); var columns = mock(MasterColumnMapper.class);
        var modelService = mock(MasterModelService.class); var users = mock(AdminUserApi.class);
        model = MasterModelDO.builder().id(1L).domainId(10L).tableCn("人员").status(1)
                .version(1).approvalEnabled(true).publishState("READY").build();
        when(modelService.validateModelReleased(1L)).thenReturn(model);
        when(models.selectById(1L)).thenReturn(model);
        when(models.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return model;
        });
        when(columns.selectListByModelId(1L)).thenReturn(List.of(
                MasterColumnDO.builder().name("id").pk(1).fieldType(5).nullable(0).build(),
                MasterColumnDO.builder().name("name").pk(0).fieldType(2).nullable(0).build()));
        when(mapper.selectByRequest(anyString())).thenAnswer(invocation -> changes.values().stream()
                .filter(c -> c.getRequestId().equals(invocation.getArgument(0))).findFirst()
                .map(c -> BeanUtils.toBean(c, MasterDataChangeDO.class)).orElse(null));
        when(mapper.selectById(anyLong())).thenAnswer(invocation -> copy(invocation.getArgument(0)));
        when(mapper.selectForUpdate(anyLong())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return copy(invocation.getArgument(0));
        });
        when(mapper.selectActive(anyLong())).thenAnswer(invocation -> changes.values().stream()
                .filter(c -> !Set.of("APPLIED", "REJECTED", "CANCELLED").contains(c.getStatus()))
                .map(c -> BeanUtils.toBean(c, MasterDataChangeDO.class)).toList());
        when(mapper.insert(any(MasterDataChangeDO.class))).thenAnswer(invocation -> {
            MasterDataChangeDO c = invocation.getArgument(0); c.setId(++nextId); c.setCreateTime(LocalDateTime.now());
            changes.put(c.getId(), BeanUtils.toBean(c, MasterDataChangeDO.class)); return 1;
        });
        when(mapper.updateById(any(MasterDataChangeDO.class))).thenAnswer(invocation -> {
            MasterDataChangeDO c = invocation.getArgument(0); changes.put(c.getId(), BeanUtils.toBean(c, MasterDataChangeDO.class)); return 1;
        });
        when(mapper.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MasterDataChangeDO>) invocation.getArgument(1);
            String assignments = wrapper.getSqlSet();
            String condition = wrapper.getSqlSegment();
            var token = java.util.regex.Pattern.compile("([a-z_]+)\\s*=\\s*#\\{ew.paramNameValuePairs.([A-Z0-9]+)\\}");
            var matchId = token.matcher(condition); Long id = null;
            while (matchId.find()) {
                if ("id".equals(matchId.group(1))) id = (Long) wrapper.getParamNameValuePairs().get(matchId.group(2));
            }
            var stored = changes.get(id); if (stored == null) return 0;
            var match = token.matcher(assignments);
            var info = com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(MasterDataChangeDO.class);
            while (match.find()) {
                String column = match.group(1); Object value = wrapper.getParamNameValuePairs().get(match.group(2));
                String property = info.getFieldList().stream().filter(field -> field.getColumn().equals(column))
                        .findFirst().orElseThrow().getProperty();
                ReflectionTestUtils.setField(stored, property, value);
            }
            return 1;
        });
        when(codes.generateForCreate(eq(1L), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        when(data.normalizeRows(eq(1L), anyList(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(data.snapshotRows(eq(1L), anyList())).thenReturn(Collections.singletonList(null));
        when(data.applyGuardedChange(eq(1L), anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new GuardedMutationResult(GuardedMutationResult.Status.APPLIED, GuardedMutationResult.Failure.NONE);
        });
        when(users.getUserMap(anyCollection())).thenReturn(Map.of());
        for (var entry : Map.<String, Object>ofEntries(
                Map.entry("changeMapper", mapper), Map.entry("logMapper", logs), Map.entry("modelMapper", models),
                Map.entry("columnMapper", columns), Map.entry("modelService", modelService), Map.entry("dataService", data),
                Map.entry("codeRuleService", codes), Map.entry("transactionManager", new TestTransactions()),
                Map.entry("bpmApi", bpm), Map.entry("userApi", users)).entrySet()) {
            ReflectionTestUtils.setField(service, entry.getKey(), entry.getValue());
        }
    }

    private MasterDataChangeDO copy(Long id) {
        return changes.containsKey(id) ? BeanUtils.toBean(changes.get(id), MasterDataChangeDO.class) : null;
    }

    private MasterDataChangeSubmitReqVO request() {
        var req = new MasterDataChangeSubmitReqVO(); req.setModelId(1L); req.setOperation("CREATE");
        req.setRequestId(UUID.randomUUID().toString());
        req.setRows(List.of(Map.of("id", "9007199254740993", "name", "人员甲"))); return req;
    }

    private MasterApprovalReviewReqVO review(Long id) {
        var req = new MasterApprovalReviewReqVO(); req.setId(id); req.setRemark("资料核验通过"); return req;
    }

    @AfterEach
    void cleanup() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }

    @Test
    void pendingSnapshotDoesNotWriteAndDuplicateDoesNotAllocateAgain() {
        var req = request(); var first = service.submit(req);
        assertEquals("PENDING", first.getStatus());
        assertEquals(first.getId(), service.submit(req).getId());
        verify(codes, times(1)).generateForCreate(eq(1L), anyList());
        verify(data, never()).applyGuardedChange(anyLong(), anyString(), anyList(), anyList());
        verifyNoInteractions(logs);
        assertThrows(ServiceException.class, () -> service.submit(request()));
    }

    @Test
    void approvedApplicationAndAuditHappenOnceOutsidePlatformLock() {
        Long id = service.submit(request()).getId();
        assertEquals("APPLIED", service.approve(review(id)).getStatus());
        assertEquals("APPLIED", service.approve(review(id)).getStatus());
        verify(data, times(1)).applyGuardedChange(eq(1L), eq("CREATE"), anyList(), anyList());
        verify(logs, times(2)).insert(any(MasterChangeLogDO.class));
        assertEquals(99L, changes.get(id).getApplyActorId());
    }

    @Test
    void rejectionHasNoExternalEffectAndIsIdempotent() {
        Long id = service.submit(request()).getId();
        assertEquals("REJECTED", service.reject(review(id)).getStatus());
        assertEquals("REJECTED", service.reject(review(id)).getStatus());
        verify(data, never()).applyGuardedChange(anyLong(), anyString(), anyList(), anyList());
        verifyNoInteractions(logs);
    }

    @Test
    void sameRequestCannotChangePayloadOrApplicant() {
        var req = request(); service.submit(req);
        req.setRows(List.of(Map.of("id", "9007199254740993", "name", "人员乙")));
        assertThrows(ServiceException.class, () -> service.submit(req));
        req.setRows(List.of(Map.of("id", "9007199254740993", "name", "人员甲")));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(100L).setTenantId(1L), new MockHttpServletRequest());
        assertThrows(ServiceException.class, () -> service.submit(req));
    }

    @Test
    void unknownResultBlocksBlindReapplicationAndRequiresVerifiedEvidence() {
        model.setApprovalEnabled(false);
        when(data.applyGuardedChange(eq(1L), anyString(), anyList(), anyList()))
                .thenReturn(new GuardedMutationResult(GuardedMutationResult.Status.UNKNOWN, GuardedMutationResult.Failure.WRITE_FAILED));
        var req = request(); var response = service.submit(req);
        assertEquals("UNKNOWN", response.getStatus());
        assertEquals("UNKNOWN", service.submit(req).getStatus());
        assertEquals("UNKNOWN", service.reconcile(response.getId()).getStatus());
        verify(data, times(1)).applyGuardedChange(eq(1L), eq("CREATE"), anyList(), anyList());
        var resolve = new MasterApprovalResolveReqVO(); resolve.setId(response.getId()); resolve.setOutcome("APPLIED");
        resolve.setReason("核对主键及全部字段"); resolve.setEvidenceRef("CASE-20261012-1");
        assertThrows(ServiceException.class, () -> service.resolve(resolve));
        when(data.snapshotRows(eq(1L), anyList())).thenReturn(req.getRows());
        assertEquals("APPLIED", service.resolve(resolve).getStatus());
        assertEquals("CASE-20261012-1", changes.get(response.getId()).getResolutionEvidence());
        assertThrows(ServiceException.class, () -> service.resolve(resolve));
        verify(data, times(1)).applyGuardedChange(eq(1L), eq("CREATE"), anyList(), anyList());
    }

    @Test
    void boundBpmCannotBeBypassedAndUsesAuthoritativeTerminalState() {
        model.setApprovalProcessKey("master_review");
        when(bpm.getProcessInstanceIdByBusinessKey(anyString(), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return CommonResult.success(null);
        });
        when(bpm.createProcessInstance(eq(99L), any())).thenReturn(CommonResult.success("process-1"));
        Long id = service.submit(request()).getId();
        assertEquals("PENDING", changes.get(id).getStatus());
        assertThrows(ServiceException.class, () -> service.approve(review(id)));
        when(bpm.getProcessInstanceStatus("process-1")).thenReturn(CommonResult.success(2));
        assertEquals("APPLIED", service.reconcile(id).getStatus());
        assertEquals("APPLIED", service.reconcile(id).getStatus());
        verify(bpm, times(1)).createProcessInstance(eq(99L), any());
        verify(data, times(1)).applyGuardedChange(eq(1L), eq("CREATE"), anyList(), anyList());
    }

    @Test
    void bpmStartTimeoutIsNotRetriedBlindly() {
        model.setApprovalProcessKey("master_review");
        when(bpm.getProcessInstanceIdByBusinessKey(anyString(), anyString())).thenReturn(CommonResult.success(null));
        when(bpm.createProcessInstance(anyLong(), any())).thenThrow(new IllegalStateException("unknown remote result"));
        var req = request(); var response = service.submit(req);
        assertEquals("WAIT_START", response.getStatus());
        service.submit(req); service.reconcile(response.getId());
        verify(bpm, times(1)).createProcessInstance(anyLong(), any());
        verify(data, never()).applyGuardedChange(anyLong(), anyString(), anyList(), anyList());
    }

    @Test
    void missingActorFailsBeforeAnyChange() {
        SecurityContextHolder.clearContext();
        assertThrows(IllegalStateException.class, () -> service.submit(request()));
        verifyNoInteractions(data, codes, bpm);
        assertTrue(changes.isEmpty());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class SecurityConfig {
        @org.springframework.context.annotation.Bean("ss")
        cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService security() {
            return mock(cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService.class);
        }
        @org.springframework.context.annotation.Bean
        cn.iocoder.yudao.module.governance.controller.admin.master.MasterDataController controller() {
            return new cn.iocoder.yudao.module.governance.controller.admin.master.MasterDataController();
        }
    }

    @Test void actualSecurityProxyRequiresPermissionForTheRequestedOperation() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("masterDataService", data);
            context.getBeanFactory().registerSingleton("modelService", mock(MasterModelService.class));
            var changeService = mock(MasterDataChangeService.class);
            context.getBeanFactory().registerSingleton("changeService", changeService);
            context.register(SecurityConfig.class); context.refresh();
            var security = context.getBean(cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService.class);
            var controller = context.getBean(cn.iocoder.yudao.module.governance.controller.admin.master.MasterDataController.class);
            when(security.hasPermission("governance:master-data:update")).thenReturn(true);
            var req = request();
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> controller.submit(req));
            verifyNoInteractions(changeService);
            when(security.hasPermission("governance:master-data:create")).thenReturn(true);
            when(changeService.submit(req)).thenReturn(new MasterDataMutationRespVO());
            assertNotNull(controller.submit(req).getCheckedData());
            verify(changeService).submit(req);
        }
    }

    @Test void minimalSpringContextWiresRuntimeAndAllNewWorkflowServices() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
             var runtime = cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone()) {
            for (String field : List.of("changeMapper", "logMapper", "modelMapper", "columnMapper", "modelService",
                    "transactionManager", "bpmApi", "userApi")) {
                context.getBeanFactory().registerSingleton(field, ReflectionTestUtils.getField(service, field));
            }
            context.getBeanFactory().registerSingleton("ruleMapper", mock(MasterCodeRuleMapper.class));
            context.getBeanFactory().registerSingleton("counterMapper", mock(MasterCodeCounterMapper.class));
            context.getBeanFactory().registerSingleton("databaseResolver", mock(MasterDatabaseResolver.class));
            context.getBeanFactory().registerSingleton("dbSwitchRuntime", runtime);
            context.register(MasterDataChangeService.class, MasterCodeRuleService.class, MasterDataServiceImpl.class);
            context.refresh();
            assertNotNull(context.getBean(MasterDataChangeService.class));
            assertNotNull(context.getBean(MasterCodeRuleService.class));
            assertSame(runtime, ReflectionTestUtils.getField(context.getBean(MasterDataServiceImpl.class), "dbSwitchRuntime"));
        }
    }
}
