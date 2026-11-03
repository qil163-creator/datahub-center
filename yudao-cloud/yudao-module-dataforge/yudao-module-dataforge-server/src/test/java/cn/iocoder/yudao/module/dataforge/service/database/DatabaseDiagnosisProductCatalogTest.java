package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisRespVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseDiagnosisProductCatalogTest {

    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 10, 15, 10, 0);

    static Stream<Arguments> productShapes() {
        return Stream.of(
                Arguments.of(ProductTypeEnum.POSTGRESQL, List.of(4, 2, 3, 1, 2, 5)),
                Arguments.of(ProductTypeEnum.HIVE, List.of(4, 2, 3, 1, 2, 5)),
                Arguments.of(ProductTypeEnum.DORIS, List.of(4, 2, 3, 1, 2, 5)),
                Arguments.of(ProductTypeEnum.MONGODB, List.of(4, 1, 2)),
                Arguments.of(ProductTypeEnum.TDENGINE, List.of(4, 2, 3, 1, 2, 5)));
    }

    @ParameterizedTest
    @MethodSource("productShapes")
    void productSchemeSelectsRowsWithoutInventingExecutedChecks(ProductTypeEnum product, List<Integer> sizes) {
        var conversion = convert(product, "SKIPPED", false,
                List.of(check("BASIC", "CONFIG", "SKIPPED")));
        var response = conversion.response();
        assertTrue(conversion.validSource());
        assertEquals(sizes, response.getGroups().stream().map(DatabaseDiagnosisRespVO.Group::getTotalCount).toList());
        assertEquals(sizes.stream().mapToInt(Integer::intValue).sum(), response.getTotalCount());
        assertEquals(response.getTotalCount(), response.getSkippedCount());
        assertEquals("SKIPPED", response.getOverallStatus());
        assertEquals(0, response.getNormalCount());
        assertFalse(response.isConnectionSuccessful());
        assertFalse(conversion.connectionFailed());
        assertTrue(response.getGroups().stream().allMatch(group -> !group.getItems().isEmpty()));
        assertEquals(switch (product) {
            case MONGODB -> "数据库访问";
            case DORIS, HIVE, TDENGINE -> "Database访问";
            default -> "目标资源访问";
        }, item(response, "SCHEMA").getCheckName());
    }

    @Test
    void executedTdengineAndDorisOperationsMustNotDisappearFromTheDiagnosis() {
        var checks = connected();
        checks.add(check("METADATA", "TABLES", "PASS"));
        checks.add(check("WRITE", "UPDATE", "FAIL"));
        var tdengine = convert(ProductTypeEnum.TDENGINE, "FAIL", true, checks).response();
        assertEquals("PASS", item(tdengine, "TABLES").getStatus());
        var doris = convert(ProductTypeEnum.DORIS, "FAIL", true, checks).response();
        assertEquals("FAIL", item(doris, "UPDATE").getStatus());
    }

    @Test
    void skippedReasonSurvivesAggregationAndMissingEvidenceRemainsUnverified() {
        var checks = connected();
        checks.add(new ConnectionDiagnosticResult.Check("METADATA", "TABLES", "基本表目录", "SKIPPED", 0,
                "无可采样对象", null, null, "NO_SAMPLE"));
        var response = convert(ProductTypeEnum.POSTGRESQL, "PASS", true, checks).response();
        assertEquals("NO_SAMPLE", item(response, "TABLES").getReasonCode());
        assertEquals("UNVERIFIED", item(response, "COLUMNS").getReasonCode());
        assertEquals("SKIPPED", item(response, "TABLES").getStatus());
    }

    @Test
    void partiallyVerifiedCompositeKeepsAuthorizationReasonWithoutInventingPass() {
        var checks = connected();
        checks.add(check("WRITE", "INSERT", "PASS"));
        checks.add(new ConnectionDiagnosticResult.Check("ADVANCED", "TRANSACTION", "事务", "SKIPPED", 0,
                "未授权", null, null, "NOT_AUTHORIZED"));
        var response = convert(ProductTypeEnum.POSTGRESQL, "PASS", true, checks).response();
        assertEquals("WARN", item(response, "INSERT").getStatus());
        assertEquals("NOT_AUTHORIZED", item(response, "INSERT").getReasonCode());
    }

    @Test
    void mongoCollectionFieldsDoNotDependOnSqlPrimaryKeys() {
        var checks = connected();
        checks.add(check("METADATA", "TABLES", "PASS"));
        checks.add(check("METADATA", "COLUMNS", "PASS"));
        checks.add(check("METADATA", "PRIMARY_KEYS", "SKIPPED"));
        var conversion = convert(ProductTypeEnum.MONGODB, "PASS", true, checks);
        var response = conversion.response();
        assertEquals("集合元数据读取", item(response, "TABLES").getCheckName());
        assertEquals("检查集合字段结构和字段类型是否可以读取", item(response, "COLUMNS").getCheckContent());
        assertEquals("PASS", item(response, "COLUMNS").getStatus());
        assertFalse(item(response, "COLUMNS").getMessage().contains("PRIMARY_KEYS"));
        assertTrue(response.isConnectionSuccessful());
        assertTrue(conversion.validSource());
        assertEquals(7, response.getTotalCount());
    }

    @Test
    void sqlPrimaryKeyEvidenceCannotSubstituteForMissingMongoFieldProbe() {
        var checks = connected();
        checks.add(check("METADATA", "PRIMARY_KEYS", "PASS"));
        var response = convert(ProductTypeEnum.MONGODB, "PASS", true, checks).response();
        assertEquals("SKIPPED", item(response, "COLUMNS").getStatus());
        assertTrue(item(response, "COLUMNS").getMessage().contains("未返回此项检查证据"));
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MONGODB"})
    void hiddenReadAndWriteCleanupWarningsRemainVisible(ProductTypeEnum product) {
        var checks = connected();
        checks.add(check("ADVANCED", "CLEANUP", "WARN"));
        checks.add(check("ADVANCED", "WRITE_CLEANUP", "WARN"));
        var conversion = convert(product, "WARN", true, checks);
        var response = conversion.response();
        var configuration = item(response, "CONFIG");
        assertEquals("WARN", configuration.getStatus());
        assertTrue(configuration.getMessage().contains("[CLEANUP]"));
        assertTrue(configuration.getMessage().contains("[WRITE_CLEANUP]"));
        assertEquals(2, configuration.getDurationMs());
        assertEquals("WARN", response.getOverallStatus());
        assertEquals(1, response.getWarningCount());
        assertTrue(conversion.validSource());
        assertTrue(response.isConnectionSuccessful());
        assertFalse(conversion.connectionFailed());
        assertEquals(response.getTotalCount(), response.getNormalCount() + response.getWarningCount()
                + response.getErrorCount() + response.getSkippedCount());
    }

    @Test
    void dorisUpdateFailureIsDisplayedWithoutChangingConnectionOutcome() {
        var checks = connected();
        checks.add(check("WRITE", "UPDATE", "FAIL"));
        checks.add(check("WRITE", "UPSERT", "WARN"));
        var conversion = convert(ProductTypeEnum.DORIS, "FAIL", true, checks);
        var response = conversion.response();
        assertEquals(17, response.getTotalCount());
        assertEquals("FAIL", item(response, "UPDATE").getStatus());
        assertTrue(item(response, "UPDATE").getMessage().contains("UPDATE"));
        assertTrue(item(response, "UPDATE").getMessage().contains("UPSERT"));
        assertEquals("SKIPPED", item(response, "CONFIG").getStatus());
        assertEquals("FAIL", response.getOverallStatus());
        assertTrue(response.isConnectionSuccessful());
        assertFalse(conversion.connectionFailed());
    }

    @Test
    void tdengineCleanupWarningsBelongToTheirVisibleOperationGroups() {
        var checks = connected();
        checks.add(check("ADVANCED", "CLEANUP", "WARN"));
        checks.add(check("ADVANCED", "WRITE_CLEANUP", "WARN"));
        var response = convert(ProductTypeEnum.TDENGINE, "WARN", true, checks).response();
        assertEquals("WARN", item(response, "ZERO_ROW_READ").getStatus());
        assertEquals("WARN", item(response, "ALTER_TEMP").getStatus());
        assertEquals("SKIPPED", item(response, "CONFIG").getStatus());
        assertTrue(response.isConnectionSuccessful());
    }

    @Test
    void hiddenFailureDoesNotTurnUnverifiedNamespaceIntoConnectionFailure() {
        var checks = new ArrayList<>(List.of(check("BASIC", "AUTH", "PASS"),
                check("TARGET", "VALIDATION", "PASS"), check("TARGET", "SCHEMA", "SKIPPED"),
                check("ADVANCED", "WRITE_CLEANUP", "FAIL")));
        var conversion = convert(ProductTypeEnum.MONGODB, "FAIL", false, checks);
        assertTrue(conversion.validSource());
        assertFalse(conversion.connectionFailed());
        assertFalse(conversion.response().isConnectionSuccessful());
        assertEquals("FAIL", item(conversion.response(), "CONFIG").getStatus());
    }

    @Test
    void hiddenChecksAreStillValidatedForGroupDuplicatesAndSummary() {
        var checks = connected();
        checks.add(check("METADATA", "PRIMARY_KEYS", "PASS"));
        checks.add(check("METADATA", "PRIMARY_KEYS", "PASS"));
        assertInvalid(convert(ProductTypeEnum.MONGODB, "PASS", true, checks));
        checks.removeLast();
        assertInvalid(convert(ProductTypeEnum.MONGODB, "FAIL", true, checks));
        checks.removeLast();
        checks.add(check("BASIC", "PRIMARY_KEYS", "PASS"));
        assertInvalid(convert(ProductTypeEnum.MONGODB, "PASS", true, checks));
    }

    @Test
    void unknownProductUsesAnExplicitGenericPlanAndCannotInventPass() {
        for (ProductTypeEnum product : new ProductTypeEnum[]{null, ProductTypeEnum.UNKNOWN}) {
            var response = convert(product, "SKIPPED", false, List.of(check("BASIC", "CONFIG", "SKIPPED"))).response();
            assertEquals(17, response.getTotalCount());
            assertEquals("SKIPPED", response.getOverallStatus());
            assertTrue(item(response, "CONFIG").getMessage().contains("通用诊断展示方案"));
            assertEquals(17, response.getSkippedCount());
        }
    }

    private static void assertInvalid(DatabaseDiagnosisConverter.Conversion conversion) {
        assertFalse(conversion.validSource());
        assertFalse(conversion.connectionFailed());
        assertFalse(conversion.response().isConnectionSuccessful());
        assertEquals("FAIL", item(conversion.response(), "CONFIG").getStatus());
    }

    private static DatabaseDiagnosisConverter.Conversion convert(ProductTypeEnum product, String status,
                                                                 boolean connected, List<ConnectionDiagnosticResult.Check> checks) {
        return DatabaseDiagnosisConverter.convertWithIntegrity(
                new ConnectionDiagnosticResult(status, connected, checks.size(), checks), CHECKED_AT, product);
    }

    private static ArrayList<ConnectionDiagnosticResult.Check> connected() {
        return new ArrayList<>(List.of(check("BASIC", "AUTH", "PASS"),
                check("TARGET", "VALIDATION", "PASS"), check("TARGET", "SCHEMA", "PASS")));
    }

    private static DatabaseDiagnosisRespVO.Item item(DatabaseDiagnosisRespVO response, String key) {
        return response.getGroups().stream().flatMap(group -> group.getItems().stream())
                .filter(item -> key.equals(item.getCheckCode())).findFirst().orElseThrow();
    }

    private static ConnectionDiagnosticResult.Check check(String group, String key, String status) {
        return new ConnectionDiagnosticResult.Check(group, key, key, status, 1, "组件安全文案", null, null);
    }
}
