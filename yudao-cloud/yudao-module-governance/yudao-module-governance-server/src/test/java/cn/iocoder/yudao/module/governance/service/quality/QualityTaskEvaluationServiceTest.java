package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualityTaskEvaluationServiceTest {
    @Test void allTwentyEightCatalogEntriesResolveAndDuplicateStrategiesKeepTheirOwnCodes() {
        var service = service();
        assertEquals(28, service.catalog().size());
        assertEquals(28, service.catalog().stream().map(QualityTaskEvaluationService.CatalogItem::ruleCode).distinct().count());
        var registry = new AuditSqlRegistry();
        service.catalog().forEach(item -> assertTrue(registry.contains(item.strategyKey()), item.ruleCode()));
        var request = request(); var evaluation = request.getEvaluations().getFirst();
        evaluation.setRuleCode("307"); evaluation.setStrategyKey("NUMERIC_RANGE_VALIDATION");
        evaluation.setQualityDim("CONSISTENCY"); evaluation.setConfig(Map.of("minValue", "0"));
        assertDoesNotThrow(() -> service.validate(request));
        evaluation.setRuleCode("306"); assertDoesNotThrow(() -> service.validate(request));
        evaluation.setQualityDim("ACCURACY");
        assertThrows(ServiceException.class, () -> service.validate(request));
    }

    @Test void structuralConfigurationDoesNotTrustActualMetadataOrNeedADatabaseDuringSave() {
        var service = service(); var request = request(); var evaluation = request.getEvaluations().getFirst();
        evaluation.setRuleCode("113"); evaluation.setStrategyKey("MODEL_STRUCTURE_VALIDATION");
        evaluation.setQualityDim("COMPLETENESS"); evaluation.setColumnNames(List.of());
        evaluation.setConfig(Map.of("expectedColumns", List.of(Map.of("name", "value", "type", "VARCHAR"))));
        assertDoesNotThrow(() -> service.validate(request));
        var context = service.context(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL, "public.sample", evaluation);
        assertNull(context.getColumnSnapshots());
        assertThrows(IllegalArgumentException.class, () -> new AuditSqlRegistry().getGenerator(evaluation.getStrategyKey()).buildCountSql(context));
        evaluation.setConfig(Map.of("expectedColumns", List.of(Map.of("name", "value")), "actualColumns", List.of(Map.of("name", "value"))));
        assertThrows(ServiceException.class, () -> service.validate(request));
        evaluation.setConfig(Map.of("expectedColumns", List.of(Map.of("name", "value"))));
        evaluation.setWhereClause("value IS NULL");
        assertThrows(ServiceException.class, () -> service.validate(request));
        evaluation.setWhereClause(null); evaluation.setColumnNames(List.of("value"));
        assertThrows(ServiceException.class, () -> service.validate(request));
    }

    @Test void relationAndStateColumnsMustBelongToTheSelectedFieldsAndAreQuoted() {
        var service = service(); var request = request(); var evaluation = request.getEvaluations().getFirst();
        evaluation.setRuleCode("116"); evaluation.setStrategyKey("NUMERIC_RELATION_VALIDATION");
        evaluation.setQualityDim("COMPLETENESS"); evaluation.setColumnNames(List.of("actual amount", "limit"));
        evaluation.setConfig(Map.of("conditions", List.of(Map.of("leftField", "actual amount", "rightField", "limit", "operator", "<="))));
        assertDoesNotThrow(() -> service.validate(request));
        var context = service.context(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL, "public.sample", evaluation);
        var sql = new AuditSqlRegistry().getGenerator(evaluation.getStrategyKey()).buildCountSql(context);
        assertTrue(sql.contains("\"actual amount\"")); assertTrue(sql.contains("\"limit\""));
        evaluation.setColumnNames(List.of("actual amount"));
        assertThrows(IllegalArgumentException.class, () -> service.context(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL, "public.sample", evaluation));
    }

    @Test void wholeRecordRuleUsesServerSelectedNonPrimaryKeyColumns() {
        var service = service(); var request = request(); var evaluation = request.getEvaluations().getFirst();
        evaluation.setRuleCode("206"); evaluation.setStrategyKey("RECORD_COMPLETENESS_VALIDATION");
        evaluation.setQualityDim("ACCURACY"); evaluation.setColumnNames(List.of()); evaluation.setConfig(Map.of());
        assertDoesNotThrow(() -> service.validate(request));
        evaluation.setColumnNames(List.of("value"));
        assertThrows(ServiceException.class, () -> service.validate(request));
    }

    @Test void freezesThePhysicalSchemaWithoutSerializingConnectionSecrets() {
        var service=service();
        var resolver=mock(cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver.class);
        ReflectionTestUtils.setField(service,"resolver",resolver);
        var route=new cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver.ConnectionInfo();
        route.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);route.setSchema("source_schema");
        route.setName("冻结源库名称"); route.setPassword("fixture-secret-not-for-snapshot");
        route.setProviderConfigurationRevision("r1");
        when(resolver.resolve(eq(8L),any())).thenReturn(route);
        var definition=new QualityTaskExecutor.GateDefinition();
        definition.setRules(List.of(QualityRuleDO.builder().executionEvaluationId(1L).build()));
        definition.setObjects(List.of(QualityCheckObjectDO.builder().id(4L).datasourceConnId(8L).tableName("sample").build()));
        service.freezeObjects(definition);
        assertEquals("\"source_schema\".\"sample\"",definition.getObjects().getFirst().getTableName());
        assertEquals("冻结源库名称",definition.getObjects().getFirst().getDatasourceConnName());
        assertFalse(JsonUtils.toJsonString(definition).contains("fixture-secret-not-for-snapshot"));

    }
    @Test void rejectsForeignObjectKeysAndNumericJsonBoundariesAndUnsafeWhere() {
        var service = service(); var request = request();
        request.getEvaluations().getFirst().setObjectKey("unknown");
        assertThrows(ServiceException.class, () -> service.validate(request));
        var rule = request.getEvaluations().getFirst(); rule.setObjectKey("new-object");
        rule.setWhereClause("1=1; DELETE FROM sample");
        assertThrows(ServiceException.class, () -> service.validate(request));
        rule.setWhereClause(null); rule.setStrategyKey("NUMERIC_RANGE_VALIDATION"); rule.setRuleCode("306");
        rule.setQualityDim("CONSISTENCY"); rule.setConfig(Map.of("minValue", 0.1));
        assertThrows(ServiceException.class, () -> service.validate(request));
        rule.setConfig(Map.of("minValue", "9007199254740993.000001", "maxValue", "9007199254740993.000002"));
        assertDoesNotThrow(() -> service.validate(request));
        for (String condition : List.of("EXISTS (SELECT 1 FROM secret)", "value IN (SELECT id FROM secret)",
                "(SELECT count(*) FROM secret)>0", "pg_read_file('/etc/passwd') IS NOT NULL",
                "pg_sleep(30) IS NULL", "value=?", "other_value=1")) {
            rule.setWhereClause(condition);
            var error = assertThrows(ServiceException.class, () -> service.validate(request), condition);
            assertTrue(error.getMessage().contains("Where 条件"));
        }
        rule.setWhereClause("(value >= 1 AND value < 20) OR value IS NULL");
        assertDoesNotThrow(() -> service.validate(request));
    }

    @Test void createsTaskOwnedEvaluationAndRoundTripsNestedJsonWithoutCrossObjectExecution() {
        var service = service(); var request = request();
        var objects = mock(QualityCheckObjectMapper.class); var relations = mock(QualityTaskObjectMapper.class);
        var evaluations = mock(QualityTaskEvaluationMapper.class);
        ReflectionTestUtils.setField(service, "objects", objects); ReflectionTestUtils.setField(service, "relations", relations);
        ReflectionTestUtils.setField(service, "evaluations", evaluations);
        when(relations.selectListByTaskId(7L)).thenReturn(List.of());
        when(evaluations.selectByTaskId(7L)).thenReturn(List.of());
        doAnswer(call -> { ((QualityCheckObjectDO)call.getArgument(0)).setId(101L); return 1; }).when(objects).insert(any(QualityCheckObjectDO.class));
        List<QualityTaskEvaluationDO> saved = new ArrayList<>();
        doAnswer(call -> { QualityTaskEvaluationDO value=call.getArgument(0); value.setId(301L); saved.add(value); return 1; })
                .when(evaluations).insert(any(QualityTaskEvaluationDO.class));
        service.validate(request); service.save(7L, request);
        assertEquals(101L, saved.getFirst().getObjectId());
        assertEquals(List.of("value"), JsonUtils.parseArray(saved.getFirst().getColumnNames(), String.class));
        when(evaluations.selectByTaskId(7L)).thenReturn(saved);
        var rules = service.executionRules(7L);
        assertEquals(1, rules.size()); assertNull(rules.getFirst().getId());
        assertEquals(301L, rules.getFirst().getExecutionEvaluationId()); assertEquals(101L, rules.getFirst().getExecutionObjectId());
        assertTrue(rules.getFirst().getParamSchema().contains("^[0-9]+$"));
        var detail = new QualityTaskRespVO(); detail.setId(7L); service.fill(detail);
        assertEquals("101", detail.getEvaluations().getFirst().getObjectKey());
        assertEquals(List.of("digit"), detail.getEvaluations().getFirst().getConfig().get("allowedTypes"));
    }

    @Test void characterSamplesUseTheActualQualityEvaluator() {
        var service = service(); var probe = new QualityTaskProbeReqVO(); probe.setEvaluation(request().getEvaluations().getFirst());
        probe.setSampleData("12333"); assertTrue(service.sample(probe).passed());
        probe.setSampleData("12A"); assertFalse(service.sample(probe).passed());
        probe.setSampleData(""); assertThrows(ServiceException.class, () -> service.sample(probe));
        probe.setSampleData("a[]\\-!"); probe.getEvaluation().setConfig(Map.of("allowedTypes", List.of("special", "letter")));
        assertTrue(service.sample(probe).passed());
    }

    static QualityTaskEvaluationService service() {
        var service = new QualityTaskEvaluationService(); var runtime=mock(DbSwitchRuntime.class);
        when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        ReflectionTestUtils.setField(service, "runtime", runtime); ReflectionTestUtils.setField(service, "registry", new AuditSqlRegistry());
        var api=mock(cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi.class);
        var source=new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO();source.setId(8L);source.setName("当前源库名称");
        when(api.getDatabaseList(null)).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(List.of(source)));
        ReflectionTestUtils.setField(service,"databaseApi",api);
        return service;
    }

    static QualityTaskSaveReqVO request() {
        var object = new QualityTaskObjectSaveReqVO(); object.setClientKey("new-object"); object.setObjectName("样例");
        object.setDatasourceConnId(8L); object.setTableName("sample");
        var rule = new QualityTaskEvaluationSaveReqVO(); rule.setObjectKey("new-object"); rule.setEvaluationName("数字检测");
        rule.setStrategyKey("CHARACTER_VALIDATION"); rule.setRuleCode("107"); rule.setQualityDim("ACCURACY");
        rule.setColumnNames(List.of("value")); rule.setConfig(Map.of("useRegex", false, "allowedTypes", List.of("digit")));
        var request = new QualityTaskSaveReqVO(); request.setObjects(List.of(object)); request.setEvaluations(List.of(rule));
        return request;
    }
}
