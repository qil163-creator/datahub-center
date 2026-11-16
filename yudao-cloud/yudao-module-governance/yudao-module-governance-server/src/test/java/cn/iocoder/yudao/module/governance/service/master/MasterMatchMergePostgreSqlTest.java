package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.annotation.DbType;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Failure.*;
import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Status.*;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 主数据相似匹配 → 合并审批 → 黄金记录追溯的端到端流程。平台表克隆自本机库的最终表定义（含 0295 的约束与部分唯一索引），
 * 位于随机 schema；外部主数据表以内存表模拟，并按原快照做条件写。
 */
@EnabledIfSystemProperty(named = "datahub.test.master.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(60)
class MasterMatchMergePostgreSqlTest {

    private static final List<String> TABLES = List.of("dh_governance_master_model", "dh_governance_master_column",
            "dh_governance_master_data_change", "dh_governance_master_change_log", "dh_governance_master_match_rule",
            "dh_governance_master_match_run", "dh_governance_master_match_group", "dh_governance_master_merge_xref");

    private Connection control;
    private String schema;
    private MasterModelMapper models;
    private MasterDataChangeMapper changes;
    private MasterChangeLogMapper logs;
    private MasterMatchRunMapper runs;
    private MasterMatchGroupMapper groups;
    private MasterMergeXrefMapper xrefs;
    private MasterDataChangeService workflow;
    private MasterMatchService match;
    /** 外部主数据表：code → 记录 */
    private final Map<String, Map<String, Object>> external = new TreeMap<>();

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.master.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        control = DriverManager.getConnection(url, "codex_test", "");
        schema = "mastermatch_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        for (String table : TABLES) {
            sql("CREATE TABLE " + table + " (LIKE \"datahub-center\"." + table + " INCLUDING ALL)");
            sql("CREATE SEQUENCE " + table + "_seq");
            sql("ALTER TABLE " + table + " ALTER COLUMN id SET DEFAULT nextval('" + schema + "." + table + "_seq')");
        }
        sql("INSERT INTO dh_governance_master_model(id,domain_id,database_id,table_name,table_cn,status,version,"
                + "approval_enabled,publish_state) VALUES(1,10,11,'md_company','company',1,1,false,'READY')");
        sql("INSERT INTO dh_governance_master_column(id,master_model_id,name,field_type,field_length,nullable,pk,sort) VALUES"
                + "(101,1,'code',2,64,0,1,0),(102,1,'name',2,64,0,0,1),(103,1,'city',2,64,1,0,2),(104,1,'phone',2,64,1,0,3)");
        put("C1", "华为技术有限公司", "深圳", null);
        put("C2", "华为技术（有限）公司", "深圳", "0755-1");
        put("C3", "腾讯科技", "深圳", "0755-2");
        put("C4", "华为技术有限公司", "北京", "010-1");
        put("C5", "腾讯 科技", "深圳", null);

        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "codex_test", "");
        var manager = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("master-match-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(interceptor);
        for (Class<?> mapper : List.of(MasterModelMapper.class, MasterColumnMapper.class, MasterDataChangeMapper.class,
                MasterChangeLogMapper.class, MasterMatchRuleMapper.class, MasterMatchRunMapper.class,
                MasterMatchGroupMapper.class, MasterMergeXrefMapper.class)) {
            config.addMapper(mapper);
        }
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        models = session.getMapper(MasterModelMapper.class);
        changes = session.getMapper(MasterDataChangeMapper.class);
        logs = session.getMapper(MasterChangeLogMapper.class);
        runs = session.getMapper(MasterMatchRunMapper.class);
        groups = session.getMapper(MasterMatchGroupMapper.class);
        xrefs = session.getMapper(MasterMergeXrefMapper.class);
        var columns = session.getMapper(MasterColumnMapper.class);

        var data = mock(MasterDataService.class);
        when(data.scanRows(anyLong(), anyInt())).thenAnswer(call -> {
            List<Map<String, Object>> rows = external.values().stream().map(row -> (Map<String, Object>) new LinkedHashMap<>(row))
                    .limit(call.<Integer>getArgument(1)).toList();
            return new MasterDataService.ScanResult(rows, external.size());
        });
        when(data.normalizeRows(anyLong(), anyList(), anyString())).thenAnswer(call -> {
            List<Map<String, Object>> rows = call.getArgument(1);
            return rows.stream().map(row -> "DELETE".equals(call.getArgument(2))
                    ? (Map<String, Object>) new LinkedHashMap<String, Object>(Map.of("code", row.get("code")))
                    : (Map<String, Object>) new LinkedHashMap<>(row)).toList();
        });
        when(data.snapshotRows(anyLong(), anyList())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            List<Map<String, Object>> rows = call.getArgument(1);
            return rows.stream().map(row -> {
                Map<String, Object> current = external.get(String.valueOf(row.get("code")));
                return current == null ? null : (Map<String, Object>) new LinkedHashMap<>(current);
            }).toList();
        });
        when(data.applyGuardedChange(anyLong(), anyString(), anyList(), anyList())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            List<Map<String, Object>> before = call.getArgument(2);
            List<Map<String, Object>> rows = call.getArgument(3);
            for (Map<String, Object> expected : before) {
                // 快照经 JSON 往返后空值字段被省略；与真实写入（按模型字段补齐）一致，空值与缺失视为相同
                if (!Objects.equals(present(expected), present(external.get(String.valueOf(expected.get("code")))))) {
                    return new GuardedMutationResult(NOT_APPLIED, CONFLICT);
                }
            }
            if ("MERGE".equals(call.getArgument(1))) {
                external.put(String.valueOf(rows.getFirst().get("code")), new LinkedHashMap<>(rows.getFirst()));
                rows.subList(1, rows.size()).forEach(row -> external.remove(String.valueOf(row.get("code"))));
            } else {
                fail("本用例只应产生合并变更");
            }
            return new GuardedMutationResult(APPLIED, NONE);
        });
        var modelService = mock(MasterModelService.class);
        when(modelService.validateModelReleased(anyLong())).thenAnswer(call -> models.selectById(call.<Long>getArgument(0)));
        var users = mock(AdminUserApi.class);
        var nick = new AdminUserRespDTO();
        nick.setId(99L);
        nick.setNickname("治理员");
        when(users.getUserMap(anyCollection())).thenReturn(Map.of(99L, nick));

        workflow = new MasterDataChangeService();
        for (var field : Map.<String, Object>ofEntries(Map.entry("changeMapper", changes), Map.entry("logMapper", logs),
                Map.entry("modelMapper", models), Map.entry("columnMapper", columns), Map.entry("modelService", modelService),
                Map.entry("dataService", data), Map.entry("codeRuleService", mock(MasterCodeRuleService.class)),
                Map.entry("transactionManager", manager), Map.entry("bpmApi", mock(BpmProcessInstanceApi.class)),
                Map.entry("userApi", users), Map.entry("xrefMapper", xrefs), Map.entry("groupMapper", groups)).entrySet()) {
            ReflectionTestUtils.setField(workflow, field.getKey(), field.getValue());
        }
        match = new MasterMatchService();
        for (var field : Map.<String, Object>ofEntries(Map.entry("ruleMapper", session.getMapper(MasterMatchRuleMapper.class)),
                Map.entry("runMapper", runs), Map.entry("groupMapper", groups), Map.entry("xrefMapper", xrefs),
                Map.entry("changeMapper", changes), Map.entry("columnMapper", columns),
                Map.entry("modelService", modelService), Map.entry("dataService", data),
                Map.entry("changeService", workflow), Map.entry("userApi", users),
                Map.entry("transactionManager", manager)).entrySet()) {
            ReflectionTestUtils.setField(match, field.getKey(), field.getValue());
        }
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
        match.saveRule(rule());
    }

    @AfterEach
    void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        if (control != null) {
            try {
                if (schema != null && schema.matches("mastermatch_[a-f0-9]{32}")) {
                    sql("DROP SCHEMA " + schema + " CASCADE");
                }
            } finally {
                control.close();
            }
        }
    }

    @Test
    void mergeUpdatesSurvivorDeletesDuplicateAndKeepsGoldenTrace() {
        MasterMatchRunRespVO run = match.run(1L);
        assertEquals("SUCCESS", run.getStatus());
        assertEquals(5, run.getScannedRows());
        assertEquals(6, run.getComparedPairs(), "深圳 4 条两两比较，北京 1 条不比较");
        assertEquals(2, run.getGroupCount());
        assertEquals("治理员", run.getRunByName());

        MasterMatchGroupRespVO huawei = pending().stream()
                .filter(group -> group.getMembers().getFirst().getKey().contains("C1")).findFirst().orElseThrow();
        MasterMatchGroupRespVO detail = match.group(huawei.getId());
        assertEquals(List.of(key("C1"), key("C2")), detail.getMembers().stream().map(MasterMatchGroupRespVO.Member::getKey).toList());
        assertEquals(1, detail.getSuggestion().getSurvivor(), "C2 字段更完整，建议保留");
        assertEquals("0755-1", detail.getSuggestion().getValues().get("phone"));
        assertFalse(detail.getPairs().isEmpty());

        Map<String, Object> values = new LinkedHashMap<>(Map.of("code", "C1", "name", "华为技术有限公司", "city", "深圳",
                "phone", "0755-1"));
        values.put("phone", "0755-9");
        var invalid = merge(huawei.getId(), "C1", List.of("C2"), values);
        assertEquals(MASTER_MATCH_MERGE_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> match.merge(invalid)).getCode(), "不能引入组外的新值");
        var notMember = merge(huawei.getId(), "C1", List.of("C3"), Map.of());
        assertEquals(MASTER_MATCH_MERGE_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> match.merge(notMember)).getCode());

        values.put("phone", "0755-1");
        var request = merge(huawei.getId(), "C1", List.of("C2"), values);
        MasterDataMutationRespVO result = match.merge(request);
        assertEquals("APPLIED", result.getStatus());
        assertEquals(result.getId(), match.merge(request).getId(), "相同请求幂等返回同一申请");
        assertEquals("0755-1", external.get("C1").get("phone"), "保留记录被更新为黄金记录");
        assertFalse(external.containsKey("C2"), "被合并记录已删除");

        MasterDataChangeDO change = changes.selectById(result.getId());
        assertEquals("MERGE", change.getOperation());
        assertEquals(huawei.getId(), change.getMatchGroupId());
        MasterMatchGroupDO merged = groups.selectById(huawei.getId());
        assertEquals("MERGED", merged.getStatus());
        assertEquals(99L, merged.getHandledBy());
        List<MasterChangeLogDO> changeLogs = logs.selectList();
        assertTrue(changeLogs.stream().allMatch(log -> "MERGE".equals(log.getChangeType())));
        assertEquals(1, changeLogs.stream().filter(log -> log.getRowOrdinal() == 0).count(), "保留记录只有电话变化");
        assertTrue(changeLogs.stream().filter(log -> log.getRowOrdinal() == 1).allMatch(log -> log.getAfterValue() == null));

        // 黄金记录 C1 再被并入 C4：原先并入 C1 的来源随之归入 C4
        var chained = workflow.submitMerge(1L, UUID.randomUUID().toString(), Map.of("code", "C4", "name", "华为技术有限公司",
                "city", "北京", "phone", "010-1"), List.of(Map.of("code", "C1")), null, null);
        assertEquals("APPLIED", chained.getStatus());
        var golden = new MasterGoldenPageReqVO();
        golden.setModelId(1L);
        PageResult<MasterGoldenRespVO> page = match.goldenPage(golden);
        assertEquals(1, page.getTotal());
        assertEquals(key("C4"), page.getList().getFirst().getGoldenKey());
        assertEquals(2, page.getList().getFirst().getMergedCount());
        assertEquals(Set.of(key("C1"), key("C2")), new HashSet<>(page.getList().getFirst().getSources().stream()
                .map(MasterGoldenRespVO.Source::getSourceKey).toList()));
        assertTrue(page.getList().getFirst().getSources().stream().anyMatch(source ->
                "华为技术（有限）公司".equals(source.getSourceRow().get("name"))), "保留被合并记录合并前的内容");

        MasterMatchRunRespVO rerun = match.run(1L);
        assertEquals(1, rerun.getGroupCount(), "华为已合并，只剩腾讯一组");
        assertEquals("STALE", groups.selectList().stream().filter(group -> !group.getId().equals(huawei.getId()))
                .filter(group -> group.getRunId().equals(run.getId())).findFirst().orElseThrow().getStatus(),
                "上一次的待处理组由新结果取代");
    }

    @Test
    void ignoredGroupsStayHiddenRejectedMergesReturnAndRunsDoNotOverlap() {
        models.updateById(MasterModelDO.builder().id(1L).approvalEnabled(true).build());
        match.run(1L);
        List<MasterMatchGroupRespVO> pending = pending();
        MasterMatchGroupRespVO huawei = pending.stream().filter(g -> g.getMembers().getFirst().getKey().contains("C1"))
                .findFirst().orElseThrow();
        MasterMatchGroupRespVO tencent = pending.stream().filter(g -> g.getMembers().getFirst().getKey().contains("C3"))
                .findFirst().orElseThrow();

        var ignore = new MasterMatchIgnoreReqVO();
        ignore.setGroupId(tencent.getId());
        ignore.setRemark("同名不同主体");
        match.ignore(ignore);
        assertEquals(MASTER_MATCH_GROUP_STATE_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> match.ignore(ignore)).getCode());

        var pendingMerge = match.merge(merge(huawei.getId(), "C2", List.of("C1"), Map.of("code", "C2",
                "name", "华为技术有限公司", "city", "深圳", "phone", "0755-1")));
        assertEquals("PENDING", pendingMerge.getStatus(), "开启审批的模型合并需审批后生效");
        assertEquals("MERGING", groups.selectById(huawei.getId()).getStatus());
        assertTrue(external.containsKey("C1"));
        var review = new MasterApprovalReviewReqVO();
        review.setId(pendingMerge.getId());
        review.setRemark("不是同一家");
        assertEquals("REJECTED", workflow.reject(review).getStatus());
        MasterMatchGroupRespVO reopened = pending().stream().filter(g -> g.getId().equals(huawei.getId()))
                .findFirst().orElseThrow();
        assertEquals("REJECTED", reopened.getChangeStatus(), "驳回后回到待处理并保留上次申请");

        MasterMatchRunRespVO rerun = match.run(1L);
        assertEquals(1, rerun.getGroupCount(), "已忽略的腾讯组不再列出，华为组按新结果重新列出");
        assertTrue(rerun.getMessage().contains("已被忽略"));
        assertEquals(List.of(key("C1"), key("C2")), pending().getFirst().getMembers().stream()
                .map(MasterMatchGroupRespVO.Member::getKey).toList());

        MasterMatchRunDO stuck = new MasterMatchRunDO();
        stuck.setModelId(1L);
        stuck.setRuleJson("{}");
        stuck.setStatus("RUNNING");
        stuck.setStartedAt(java.time.LocalDateTime.now());
        stuck.setRunBy(99L);
        runs.insert(stuck);
        assertEquals(MASTER_MATCH_RUNNING.getCode(),
                assertThrows(ServiceException.class, () -> match.run(1L)).getCode(), "同一模型不能并发执行");
        stuck.setStartedAt(java.time.LocalDateTime.now().minusHours(1));
        runs.updateById(stuck);
        assertEquals("SUCCESS", match.run(1L).getStatus(), "中断遗留的执行在超时后释放");
        assertEquals("FAILED", runs.selectById(stuck.getId()).getStatus());
    }

    private List<MasterMatchGroupRespVO> pending() {
        var req = new MasterMatchGroupPageReqVO();
        req.setModelId(1L);
        req.setStatus("PENDING");
        return match.groupPage(req).getList();
    }

    private static MasterMatchRuleVO rule() {
        var rule = new MasterMatchRuleVO();
        rule.setModelId(1L);
        var name = new MasterMatchRuleVO.Field();
        name.setName("name");
        name.setAlgorithm("TEXT");
        name.setWeight(3);
        var phone = new MasterMatchRuleVO.Field();
        phone.setName("phone");
        phone.setAlgorithm("EXACT");
        phone.setWeight(1);
        rule.setFields(List.of(name, phone));
        rule.setBlockingFields(List.of("city"));
        rule.setThreshold("0.9");
        rule.setSurvivorship(Map.of("phone", "MOST_COMPLETE"));
        return rule;
    }

    private static MasterMatchMergeReqVO merge(Long groupId, String survivor, List<String> merged, Map<String, Object> values) {
        var req = new MasterMatchMergeReqVO();
        req.setGroupId(groupId);
        req.setRequestId(UUID.randomUUID().toString());
        req.setSurvivorKey(key(survivor));
        req.setMergedKeys(merged.stream().map(MasterMatchMergePostgreSqlTest::key).toList());
        req.setValues(values);
        return req;
    }

    private static String key(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private static Map<String, Object> present(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        row.forEach((name, value) -> {
            if (value != null) {
                result.put(name, value);
            }
        });
        return result;
    }

    private void put(String code, String name, String city, String phone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("name", name);
        row.put("city", city);
        row.put("phone", phone);
        external.put(code, row);
    }

    private void sql(String query) throws Exception {
        try (Statement statement = control.createStatement()) {
            statement.execute(query);
        }
    }
}
