package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Status.*;
import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Failure.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 私有本机库内克隆最终表定义；不读取基线业务数据，所有新序列与记录位于随机 schema。 */
@EnabledIfSystemProperty(named = "datahub.test.master.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class MasterDataWorkflowPostgreSqlTest {
    private Connection control;
    private String schema;
    private MasterModelMapper models;
    private MasterDataChangeMapper changes;
    private MasterChangeLogMapper logs;
    private MasterCodeRuleMapper rules;
    private MasterCodeCounterMapper counters;
    private MasterDataChangeService workflow;
    private MasterCodeRuleService codes;
    private final Map<String, Map<String, Object>> externalRows = new ConcurrentHashMap<>();
    private final AtomicInteger externalWrites = new AtomicInteger();
    private volatile GuardedMutationResult externalResult;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.master.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        control = DriverManager.getConnection(url, "codex_test", "");
        schema = "masterworkflow_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        for (String table : List.of("dh_governance_master_domain", "dh_governance_master_model", "dh_governance_master_column",
                "dh_governance_master_code_rule", "dh_governance_master_code_counter", "dh_governance_master_data_change", "dh_governance_master_change_log")) {
            sql("CREATE TABLE " + table + " (LIKE \"datahub-center\"." + table + " INCLUDING ALL)");
            sql("CREATE SEQUENCE " + table + "_seq");
            // LIKE 的默认序列仍指向来源表，必须改为本 schema 的独立序列。
            sql("ALTER TABLE " + table + " ALTER COLUMN id SET DEFAULT nextval('" + schema + "." + table + "_seq')");
        }
        sql("INSERT INTO dh_governance_master_domain(id,code,name) VALUES(10,'person','person'),(20,'organization','organization')");
        sql("INSERT INTO dh_governance_master_model(id,domain_id,database_id,table_name,table_cn,status,version,approval_enabled,publish_state) VALUES(1,10,11,'md_person','person',1,1,true,'READY'),(2,20,22,'md_org','organization',1,1,true,'READY')");
        sql("INSERT INTO dh_governance_master_column(id,master_model_id,name,field_type,field_length,nullable,pk,sort) VALUES(101,1,'code',2,64,0,1,0),(102,1,'name',2,64,0,0,1),(201,2,'code',2,64,0,1,0),(202,2,'name',2,64,0,0,1)");
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "codex_test", "");
        var manager = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("master-workflow-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();
        config.addInterceptor(interceptor);
        for (Class<?> mapper : List.of(MasterModelMapper.class, MasterColumnMapper.class, MasterDataChangeMapper.class,
                MasterChangeLogMapper.class, MasterCodeRuleMapper.class, MasterCodeCounterMapper.class)) config.addMapper(mapper);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        models = session.getMapper(MasterModelMapper.class); changes = session.getMapper(MasterDataChangeMapper.class);
        logs = session.getMapper(MasterChangeLogMapper.class); rules = session.getMapper(MasterCodeRuleMapper.class);
        counters = session.getMapper(MasterCodeCounterMapper.class); var columns = session.getMapper(MasterColumnMapper.class);
        MasterCodeRuleService codeTarget = new MasterCodeRuleService();
        ReflectionTestUtils.setField(codeTarget, "ruleMapper", rules); ReflectionTestUtils.setField(codeTarget, "counterMapper", counters);
        ReflectionTestUtils.setField(codeTarget, "modelMapper", models); ReflectionTestUtils.setField(codeTarget, "columnMapper", columns);
        ProxyFactory proxy = new ProxyFactory(codeTarget); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        codes = (MasterCodeRuleService) proxy.getProxy();
        var data = mock(MasterDataService.class);
        when(data.normalizeRows(anyLong(), anyList(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(data.snapshotRows(anyLong(), anyList())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            List<Map<String, Object>> values = call.getArgument(1);
            return values.stream().map(row -> externalRows.get(String.valueOf(row.get("code")))).toList();
        });
        externalResult = new GuardedMutationResult(APPLIED, NONE);
        when(data.applyGuardedChange(anyLong(), anyString(), anyList(), anyList())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            externalWrites.incrementAndGet();
            List<Map<String, Object>> values = call.getArgument(3);
            // UNKNOWN 分支模拟服务端已写入、调用方未收到提交确认。
            if (externalResult.status() != NOT_APPLIED) values.forEach(row -> externalRows.put(String.valueOf(row.get("code")), new LinkedHashMap<>(row)));
            return externalResult;
        });
        var modelService = mock(MasterModelService.class);
        when(modelService.validateModelReleased(anyLong())).thenAnswer(call -> models.selectById(call.<Long>getArgument(0)));
        var users = mock(AdminUserApi.class); when(users.getUserMap(anyCollection())).thenReturn(Map.of());
        workflow = new MasterDataChangeService();
        for (var field : Map.<String, Object>ofEntries(Map.entry("changeMapper", changes), Map.entry("logMapper", logs),
                Map.entry("modelMapper", models), Map.entry("columnMapper", columns), Map.entry("modelService", modelService),
                Map.entry("dataService", data), Map.entry("codeRuleService", codes), Map.entry("transactionManager", manager),
                Map.entry("bpmApi", mock(BpmProcessInstanceApi.class)), Map.entry("userApi", users)).entrySet()) {
            ReflectionTestUtils.setField(workflow, field.getKey(), field.getValue());
        }
        login();
    }

    @AfterEach
    void cleanup() throws Exception {
        clearContext();
        if (control != null) try { if (schema != null && schema.matches("masterworkflow_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE"); }
        finally { control.close(); }
    }

    @Test
    void persistedNativeApprovalAppliesOnceAndAuditRemainsAvailableWithoutTenantContext() {
        var req = request("P001"); var pending = workflow.submit(req);
        MasterDataChangeDO persisted = changes.selectById(pending.getId());
        assertEquals("PENDING", persisted.getStatus()); assertTrue(persisted.getRowsJson().contains("P001"));
        assertEquals("[null]", persisted.getBeforeJson()); assertEquals(0, externalWrites.get()); assertEquals(0, logs.selectList().size());
        assertEquals(pending.getId(), workflow.submit(req).getId());
        assertEquals("APPLIED", workflow.approve(review(pending.getId())).getStatus());
        assertEquals("APPLIED", workflow.approve(review(pending.getId())).getStatus());
        assertEquals(1, externalWrites.get()); assertEquals(2, logs.selectList().size());
        assertTrue(logs.selectList().stream().allMatch(log -> Long.valueOf(99).equals(log.getChangedBy())));
        assertEquals(Set.of("code", "name"), new HashSet<>(logs.selectList().stream().map(MasterChangeLogDO::getPropertyName).toList()));
        assertEquals(99L, changes.selectById(pending.getId()).getReviewerId());
        TenantContextHolder.clear(); assertNotNull(changes.selectById(pending.getId())); assertEquals(2, logs.selectList().size());
    }

    @Test
    void unknownResolveChecksObservedSnapshotAndClearsPersistedError() {
        models.updateById(MasterModelDO.builder().id(1L).approvalEnabled(false).build());
        externalResult = new GuardedMutationResult(UNKNOWN, WRITE_FAILED);
        var req = request("U001"); var unknown = workflow.submit(req);
        assertEquals("UNKNOWN", unknown.getStatus()); assertNotNull(changes.selectById(unknown.getId()).getLastError());
        assertEquals("UNKNOWN", workflow.submit(req).getStatus()); assertEquals(1, externalWrites.get());
        var resolve = new MasterApprovalResolveReqVO(); resolve.setId(unknown.getId()); resolve.setOutcome("APPLIED");
        resolve.setReason("verified all values"); resolve.setEvidenceRef("CASE-PG-001");
        assertEquals("APPLIED", workflow.resolve(resolve).getStatus());
        MasterDataChangeDO applied = changes.selectById(unknown.getId());
        assertNull(applied.getLastError()); assertEquals("CASE-PG-001", applied.getResolutionEvidence());
        assertEquals(99L, applied.getResolvedBy()); assertEquals(2, logs.selectList().size()); assertEquals(1, externalWrites.get());
    }

    @Test
    void concurrentCodesStayUniqueAndEnabledSwitchPreservesModelCounters() throws Exception {
        Long firstRule = codes.create(rule(1L, "RULE_A", "A"));
        CountDownLatch start = new CountDownLatch(1);
        List<String> generated = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<List<String>> issue = () -> {
                login();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (int i = 0; i < 25; i++) rows.add(Map.of("name", "person"));
                    return codes.generateForCreate(1L, rows).stream().map(row -> String.valueOf(row.get("code"))).toList();
                } finally { clearContext(); }
            };
            Future<List<String>> first = pool.submit(issue); Future<List<String>> second = pool.submit(issue); start.countDown();
            generated.addAll(first.get(10, TimeUnit.SECONDS)); generated.addAll(second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(50, new HashSet<>(generated).size()); assertTrue(generated.contains("A-001")); assertTrue(generated.contains("A-050"));
        assertEquals(51L, counters.selectOne(MasterCodeCounterDO::getModelId, 1L).getNextValue());
        Long secondRule = codes.create(rule(1L, "RULE_B", "B"));
        assertEquals(1, rules.selectById(firstRule).getStatus()); assertEquals(secondRule, rules.selectEnabled(1L).getId());
        assertEquals("B-051", codes.generateForCreate(1L, List.of(Map.of("name", "person"))).getFirst().get("code"));
        login(); assertNotNull(rules.selectById(secondRule));
        codes.create(rule(2L, "RULE_T", "T"));
        assertEquals("T-001", codes.generateForCreate(2L, List.of(Map.of("name", "person"))).getFirst().get("code"));
        login(); assertEquals(52L, counters.selectOne(MasterCodeCounterDO::getModelId, 1L).getNextValue());
    }

    private static MasterCodeRuleSaveReqVO rule(Long modelId, String code, String prefix) {
        var req = new MasterCodeRuleSaveReqVO(); req.setModelId(modelId); req.setCode(code); req.setName(code); req.setFieldName("code"); req.setStatus(0);
        var constant = new MasterCodeSegmentVO(); constant.setSegmentType("CONSTANT"); constant.setSegmentValue(prefix); constant.setSepChar("-");
        var sequence = new MasterCodeSegmentVO(); sequence.setSegmentType("SEQUENCE"); sequence.setSegmentValue("3");
        req.setSegments(List.of(constant, sequence)); return req;
    }
    private static MasterDataChangeSubmitReqVO request(String code) {
        var req = new MasterDataChangeSubmitReqVO(); req.setModelId(1L); req.setOperation("CREATE"); req.setRequestId(UUID.randomUUID().toString());
        req.setRows(List.of(Map.of("code", code, "name", "person"))); return req;
    }
    private static MasterApprovalReviewReqVO review(Long id) {
        var req = new MasterApprovalReviewReqVO(); req.setId(id); req.setRemark("verified"); return req;
    }
    private static void login() {
        TenantContextHolder.clear(); TenantContextHolder.setIgnore(false);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
    }
    private static void clearContext() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    private void sql(String query) throws Exception { try (Statement statement = control.createStatement()) { statement.execute(query); } }
}
