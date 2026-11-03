package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

/** Only creates/drops a random schema on an explicitly selected disposable loopback instance. */
@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class QualityOrgKeyTidbTest {
    private Connection connection;
    private Statement sql;
    private String schema;

    @BeforeEach
    void setup() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),
                System.getProperty("datahub.test.tidb.user", "root"), "");
        sql = connection.createStatement();
        schema = "quality_org_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE DATABASE " + schema);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (sql != null && schema != null) sql.execute("DROP DATABASE IF EXISTS " + schema);
        } finally {
            if (sql != null) sql.close();
            if (connection != null) connection.close();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SMALLINT|-32768|32767", "INT|-2147483648|2147483647",
            "BIGINT|-9223372036854775808|9223372036854775807"
    })
    void groupsSignedInstitutionsExactlyAndSharesTheGlobalKeyBudget(String type, String low, String high) throws Exception {
        create(type);
        sql.execute("INSERT INTO " + table() + " VALUES(10," + low + ",NULL),(2," + low
                + ",1),(10," + high + ",1)");
        var read = grouped(3, 2);
        assertTrue(read.complete(), read.reasonCode());
        assertEquals(List.of(low, high), read.groups().stream().map(AuditKeySnapshot.Group::key).toList());
        assertTrue(read.groups().stream().allMatch(group -> group.snapshot().complete()));
        assertEquals(2, read.groups().getFirst().snapshot().total());
        assertEquals(1, read.groups().getFirst().snapshot().fail());
        assertEquals(read, JsonUtils.parseObject(JsonUtils.toJsonString(read), AuditKeySnapshot.Grouped.class));
        var overflow = grouped(2, 2);
        assertFalse(overflow.complete()); assertEquals("KEY_LIMIT_EXCEEDED", overflow.reasonCode());
        assertTrue(overflow.groups().isEmpty());
        var tooManyGroups = grouped(3, 1);
        assertFalse(tooManyGroups.complete()); assertEquals("GROUP_LIMIT_EXCEEDED", tooManyGroups.reasonCode());
        assertTrue(tooManyGroups.groups().isEmpty());
    }

    @Test
    void ownInstitutionCanPassWhileAnotherFailsButMovingOrReplacingRowsCannotPass() throws Exception {
        create("BIGINT");
        sql.execute("INSERT INTO " + table()
                + " VALUES(9007199254740992,9,NULL),(9007199254740993,9,1),(3,10,NULL)");
        var baseline = run(10);
        sql.execute("UPDATE " + table() + " SET amount=1 WHERE org_code=9");
        var repaired = run(20);
        assertEquals("FAIL", repaired.audit().getResultType());
        var accepted = compare(baseline, repaired);
        assertTrue(accepted.comparable(), accepted.reason()); assertEquals("PASS", accepted.resultType());

        sql.execute("UPDATE " + table() + " SET id=9007199254740994 WHERE id=9007199254740992");
        assertReason("ROW_POPULATION_CHANGED", compare(baseline, run(21)));
        sql.execute("UPDATE " + table() + " SET id=9007199254740992 WHERE id=9007199254740994");
        // Whole-table keys are unchanged; swap institutions without changing either group's size.
        sql.execute("UPDATE " + table() + " SET org_code=CASE WHEN id=3 THEN 9 "
                + "WHEN id=9007199254740992 THEN 10 ELSE org_code END, amount=1");
        assertReason("ROW_POPULATION_CHANGED", compare(baseline, run(22)));
        sql.execute("UPDATE " + table() + " SET org_code=10");
        assertReason("ORG_COVERAGE_UNPROVEN", compare(baseline, run(23)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "BIGINT UNSIGNED|18446744073709551615", "DECIMAL(20,0)|9", "CHAR(64)|'9'",
            "VARCHAR(5000)|REPEAT('9',4000)", "BIGINT|NULL", "TINYINT|9", "MEDIUMINT|9",
            "ENUM('a','b')|'a'", "SET('a','b')|'a'", "VARBINARY(64)|'a'"
    })
    void unsupportedInstitutionTypesNeverProduceBorrowableEvidence(String type, String value) throws Exception {
        create(type);
        sql.execute("INSERT INTO " + table() + " VALUES(1," + value + ",NULL)");
        var grouped = grouped(10, 10);
        assertFalse(grouped.complete()); assertEquals("GROUP_KEY_UNSUPPORTED", grouped.reasonCode());
        assertTrue(grouped.groups().isEmpty());
        var baseline = run(10);
        sql.execute("UPDATE " + table() + " SET amount=1");
        var repaired = run(20);
        assertEquals("PASS", repaired.audit().getResultType());
        assertFalse(compare(baseline, repaired).comparable());
    }

    @Test
    void duplicateAndNullKeysInOwnInstitutionCannotPass() throws Exception {
        create("BIGINT");
        sql.execute("INSERT INTO " + table() + " VALUES(1,9,NULL),(2,9,1)");
        var baseline = run(10);
        sql.execute("UPDATE " + table() + " SET amount=1, id=1");
        assertReason("ROW_COVERAGE_UNPROVEN", compare(baseline, run(20)));
        sql.execute("UPDATE " + table() + " SET id=NULL");
        assertReason("ROW_COVERAGE_UNPROVEN", compare(baseline, run(21)));
    }

    @Test
    void adjacentLargeInstitutionIdsRemainDistinct() throws Exception {
        create("BIGINT");
        sql.execute("INSERT INTO " + table() + " VALUES(1,9007199254740992,NULL),(1,9007199254740993,1)");
        var read = grouped(2, 2);
        assertTrue(read.complete());
        assertEquals(List.of("9007199254740992", "9007199254740993"),
                read.groups().stream().map(AuditKeySnapshot.Group::key).toList());
        assertTrue(read.groups().stream().allMatch(group -> group.snapshot().complete()));
    }

    @Test
    void textInstitutionsKeepCaseTrailingSpaceAndLeadingZeroIdentities() throws Exception {
        create("VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        sql.execute("INSERT INTO " + table()
                + " VALUES(1,'A',NULL),(1,'a',1),(1,'A ',1),(1,'0009',1),(1,'9',1)");
        var read = grouped(10, 10);
        assertTrue(read.complete(), read.reasonCode());
        assertEquals(List.of("0009", "9", "A", "A ", "a"),
                read.groups().stream().map(AuditKeySnapshot.Group::key).toList());
        assertTrue(read.groups().stream().allMatch(group -> group.snapshot().complete()));
    }

    @Test
    void collatedTextInstitutionsAreSplitBeforeCountsAreAggregated() throws Exception {
        create("VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',NULL),(1,'a',1),(1,'A ',1)");
        try (var counts = sql.executeQuery("SELECT COUNT(*) FROM " + table() + " GROUP BY org_code")) {
            assertTrue(counts.next()); assertEquals(3, counts.getLong(1)); assertFalse(counts.next());
        }
        var read = grouped(10, 10);
        assertTrue(read.complete()); assertEquals(3, read.groups().size());
        assertTrue(read.groups().stream().allMatch(group -> group.snapshot().complete()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci|12",
            "TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci|-1",
            "TINYTEXT CHARACTER SET utf8mb4|-1", "MEDIUMTEXT CHARACTER SET utf8mb4|-1",
            "LONGTEXT CHARACTER SET utf8mb4|-1"
    })
    void textGroupsRemainExactUnderSmallLobSortPrefixes(String definition, int jdbcType) throws Exception {
        create(definition);
        sql.execute("SET SESSION max_sort_length=4");
        try (var insert = connection.prepareStatement("INSERT INTO " + table() + " VALUES(1,?,NULL)")) {
            for (String group : List.of("prefix-A", "prefix-a", "prefix-A ", "中国机构", "机构😀", "e\u0301", "é")) {
                insert.setString(1, group); insert.executeUpdate();
            }
        }
        var read = grouped(10, 10);
        assertTrue(read.complete(), read.reasonCode()); assertEquals(7, read.groups().size());
        assertEquals(AuditKeySnapshot.TIDB_TEXT_GROUP_ENCODING, read.groupEncoding());
        assertEquals(jdbcType, read.groupJdbcType());
        assertTrue(read.groups().stream().allMatch(group -> group.snapshot().complete()));
        assertEquals(read, JsonUtils.parseObject(JsonUtils.toJsonString(read), AuditKeySnapshot.Grouped.class));
    }

    @Test
    void textInstitutionAcceptanceAndSavedLocationUseTheSameExactScope() throws Exception {
        create("VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',NULL),(2,'A',1),(3,'a',NULL),(4,'A ',NULL)");
        var baseline = run(10, true);
        assertEquals(3, baseline.issues().stream().filter(issue -> "ORG".equals(issue.getStatScope())).count());
        for (var issue : baseline.issues()) {
            if (!"ORG".equals(issue.getStatScope())) continue;
            try (var rows = sql.executeQuery(issue.getLocationSql())) {
                assertTrue(rows.next()); assertEquals(issue.getRegionCode(), rows.getString("org_code"));
                assertFalse(rows.next());
            }
        }
        sql.execute("UPDATE " + table() + " SET amount=1 WHERE CAST(org_code AS BINARY)=X'41'");
        var repaired = run(20);
        assertEquals("FAIL", repaired.audit().getResultType());
        var accepted = compare(baseline, repaired, "A");
        assertTrue(accepted.comparable(), accepted.reason()); assertEquals("PASS", accepted.resultType());
        sql.execute("UPDATE " + table() + " SET org_code=CASE WHEN id=1 THEN 'a' WHEN id=3 THEN 'A' ELSE org_code END, amount=1");
        assertReason("ROW_POPULATION_CHANGED", compare(baseline, run(21), "A"));
    }

    @Test
    void longBlankNullOrZeroCharacterTextGroupsDoNotProducePartialProof() throws Exception {
        create("TEXT CHARACTER SET utf8mb4");
        for (String invalid : java.util.Arrays.asList("x".repeat(65), "中".repeat(100), " ", "", "a\0b", null)) {
            sql.execute("DELETE FROM " + table());
            try (var insert = connection.prepareStatement("INSERT INTO " + table() + " VALUES(1,?,NULL),(2,'valid',1)")) {
                insert.setString(1, invalid); insert.executeUpdate();
            }
            var read = grouped(10, 10);
            assertFalse(read.complete()); assertTrue(read.groups().isEmpty());
        }
    }

    @Test
    void textGroupLimitsKeepNoPartialProofAndDuplicateKeysCannotQualify() throws Exception {
        create("VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'A',NULL),(2,'A',1),(3,'a',NULL)");
        assertEquals("KEY_LIMIT_EXCEEDED", grouped(2, 10).reasonCode());
        assertTrue(grouped(2, 10).groups().isEmpty());
        assertEquals("GROUP_LIMIT_EXCEEDED", grouped(10, 1).reasonCode());
        var baseline = run(10);
        sql.execute("UPDATE " + table() + " SET id=1,amount=1 WHERE CAST(org_code AS BINARY)=X'41'");
        assertReason("ROW_COVERAGE_UNPROVEN", compare(baseline, run(20), "A"));
    }

    @Test
    void quotedBackslashAndUnicodeLocationRemainsExactUnderEitherSqlMode() throws Exception {
        create("VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        String[] values = {"a\\' OR 1=1 -- ", "A\\' OR 1=1 -- ", "中国😀", "0009", "9"};
        try (var insert = connection.prepareStatement("INSERT INTO " + table() + " VALUES(?,?,NULL)")) {
            for (int index = 0; index < values.length; index++) {
                insert.setLong(1, index + 1); insert.setString(2, values[index]); insert.executeUpdate();
            }
        }
        for (String mode : List.of("ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES", "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_BACKSLASH_ESCAPES")) {
            sql.execute("SET SESSION sql_mode='" + mode + "'");
            var baseline = run(10, true);
            var issues = baseline.issues().stream().filter(issue -> "ORG".equals(issue.getStatScope())).toList();
            assertEquals(values.length, issues.size());
            for (var issue : issues) {
                try (var rows = sql.executeQuery(issue.getLocationSql())) {
                    assertTrue(rows.next()); assertEquals(issue.getRegionCode(), rows.getString("org_code"));
                    assertFalse(rows.next());
                }
            }
        }
    }

    @Test
    void duplicateKeysInAnotherInstitutionDoNotReplaceOwnValidProof() throws Exception {
        create("BIGINT");
        sql.execute("INSERT INTO " + table() + " VALUES(1,9,NULL),(2,9,1),(3,10,NULL),(3,10,NULL)");
        var baseline = run(10);
        sql.execute("UPDATE " + table() + " SET amount=1 WHERE org_code=9");
        var repaired = run(20);
        assertEquals("FAIL", repaired.audit().getResultType());
        var result = compare(baseline, repaired);
        assertTrue(result.comparable(), result.reason()); assertEquals("PASS", result.resultType());
    }

    private void create(String orgType) throws Exception {
        sql.execute("CREATE TABLE " + table() + "(id BIGINT, org_code " + orgType + ", amount BIGINT) ENGINE=InnoDB");
    }

    @Test
    void historicalOrForeignInstitutionProofCannotBePromotedByANewTextRead() throws Exception {
        create("VARCHAR(64)");
        sql.execute("INSERT INTO " + table() + " VALUES(1,'9',NULL)");
        var before = run(10);
        sql.execute("UPDATE " + table() + " SET amount=1");
        var after = run(20);
        assertTrue(compare(before, after).comparable());
        String original = before.audit().getReadEvidence();
        for (java.util.function.Consumer<QualityReadEvidence.OrgScope> invalid :
                List.<java.util.function.Consumer<QualityReadEvidence.OrgScope>>of(
                        scope -> scope.setSchemaVersion(1), scope -> scope.setGroupEncoding(null),
                        scope -> scope.setGroupEncoding(AuditKeySnapshot.MYSQL_TEXT_GROUP_ENCODING),
                        scope -> scope.setGroupEncoding(AuditKeySnapshot.TIDB_INTEGER_GROUP_ENCODING),
                        scope -> scope.setGroupJdbcType(java.sql.Types.CHAR),
                        scope -> scope.setGroupJdbcType(java.sql.Types.LONGVARCHAR),
                        scope -> scope.setKeyCaptureReason("GROUP_KEY_UNSUPPORTED"),
                        scope -> scope.getGroups().getFirst().keyScope().setComplete(false))) {
            var evidence = JsonUtils.parseObject(original, QualityReadEvidence.class);
            invalid.accept(evidence.getOrgScope());
            before.audit().setReadEvidence(JsonUtils.toJsonString(evidence));
            assertFalse(compare(before, after).comparable());
        }
        before.audit().setReadEvidence(original);
        assertTrue(compare(before, after).comparable());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "BIGINT|9|10|VARCHAR(500)", "BIGINT|9|10|TEXT",
            "VARCHAR(64)|'9'|'09'|VARCHAR(500)", "TEXT|'9'|'09'|TEXT",
            "TEXT|'9'|'09'|LONGTEXT"
    })
    void textRecordKeysCannotBeReplacedOrTransferredBetweenInstitutions(
            String orgType, String own, String other, String keyType) throws Exception {
        sql.execute("CREATE TABLE " + table() + "(id " + keyType
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci, org_code " + orgType
                + ", amount BIGINT) ENGINE=InnoDB");
        sql.execute("SET SESSION max_sort_length=4");
        sql.execute("INSERT INTO " + table() + " VALUES('A'," + own + ",NULL),('a'," + own
                + ",1),('A '," + own + ",1),('other'," + other + ",NULL)");
        var read = grouped(10, 10);
        assertTrue(read.complete(), read.reasonCode());
        assertTrue(read.groups().stream().allMatch(group -> group.snapshot().complete()), read.toString());
        assertTrue(read.groups().stream().allMatch(group ->
                "SHA256_SORTED_TIDB_UTF8_BYTES_V1".equals(group.snapshot().algorithm())));
        assertEquals(read, JsonUtils.parseObject(JsonUtils.toJsonString(read), AuditKeySnapshot.Grouped.class));
        assertEquals("KEY_LIMIT_EXCEEDED", grouped(3, 10).reasonCode());
        assertEquals("GROUP_LIMIT_EXCEEDED", grouped(10, 1).reasonCode());
        var baseline = run(10);
        sql.execute("UPDATE " + table() + " SET amount=1 WHERE org_code=" + own);
        var fixed = compare(baseline, run(20));
        assertTrue(fixed.comparable(), fixed.reason()); assertEquals("PASS", fixed.resultType());
        sql.execute("UPDATE " + table() + " SET id='replacement' WHERE CAST(id AS BINARY)=X'41'");
        assertReason("ROW_POPULATION_CHANGED", compare(baseline, run(21)));
        sql.execute("UPDATE " + table() + " SET id='A' WHERE id='replacement'");
        sql.execute("UPDATE " + table() + " SET org_code=CASE WHEN CAST(id AS BINARY)=X'41' THEN "
                + other + " WHEN id='other' THEN " + own + " ELSE org_code END, amount=1");
        assertReason("ROW_POPULATION_CHANGED", compare(baseline, run(22)));
        sql.execute("INSERT INTO " + table() + " VALUES('a'," + own + ",1)");
        assertReason("ROW_COVERAGE_UNPROVEN", compare(baseline, run(23)));
        sql.execute("UPDATE " + table() + " SET id=NULL WHERE org_code=" + own);
        assertReason("ROW_COVERAGE_UNPROVEN", compare(baseline, run(24)));
        sql.execute("UPDATE " + table() + " SET id=REPEAT('x',257) WHERE org_code=" + own);
        assertReason("ROW_COVERAGE_UNPROVEN", compare(baseline, run(25)));
    }

    private String table() { return schema + ".sample"; }

    private AuditKeySnapshot.Grouped grouped(int keys, int groups) throws Exception {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.TIDB); context.setTableName(table());
        context.setColumn("amount"); context.setConfig(Map.of());
        return AuditKeySnapshot.readGrouped(connection, new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION"),
                context, "id", "org_code", keys, groups, 5);
    }

    private record Run(QualityTaskInstanceDO instance, QualityAuditLogDO audit, List<QualityIssueDataDO> issues) { }

    private Run run(long id) {
        return run(id, false);
    }

    private Run run(long id, boolean collect) {
        var task = QualityTaskDO.builder().id(1L).orgField("org_code").build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY")
                .tableName(table()).build();
        var rule = QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema("{\"column\":\"amount\",\"pkColumn\":\"id\"}").collectIssueDetail(collect ? 1 : 0).build();
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task); definition.setRules(List.of(rule)); definition.setObjects(List.of(object));
        var instance = QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS")
                .definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        var route = new MasterDatabaseResolver.ConnectionInfo();
        route.setDatabaseId(4L); route.setProductType(ProductTypeEnum.TIDB); route.setUsageType(1);
        route.setSchema(schema); route.setConfigurationRevision("test-revision");
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor, "auditLogMapper", mock(QualityAuditLogMapper.class));
        List<QualityIssueDataDO> issues = new ArrayList<>();
        var issueMapper = mock(QualityIssueDataMapper.class);
        doAnswer(invocation -> { issues.add(invocation.getArgument(0)); return 1; })
                .when(issueMapper).insert(any(QualityIssueDataDO.class));
        ReflectionTestUtils.setField(executor, "issueDataMapper", issueMapper);
        QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor, id, task,
                connection, route, object, rule);
        assertNotNull(audit); audit.setId(id * 10); instance.setEndTime(LocalDateTime.now());
        assertNotEquals("ERROR", audit.getResultType(), audit.getErrorDetail());
        return new Run(instance, audit, issues);
    }

    private QualityRecheckEvidence.Comparison compare(Run baseline, Run current) {
        return compare(baseline, current, "9");
    }

    private QualityRecheckEvidence.Comparison compare(Run baseline, Run current, String group) {
        var issue = QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L)
                .instanceId(baseline.instance().getId()).auditLogId(baseline.audit().getId()).tableName(table())
                .checkField("amount").statScope("ORG").regionCode(group).issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(), issue,
                baseline.instance(), baseline.audit(), current.instance(), current.audit(), baseline.instance().getEndTime());
    }

    private static void assertReason(String expected, QualityRecheckEvidence.Comparison result) {
        assertFalse(result.comparable()); assertEquals(expected, result.evidence().get("reasonCode"));
    }
}
