package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real authenticated MongoDB + pinned driver + public runtime, without shell-JavaScript data conversion. */
@EnabledIfSystemProperty(named = "datahub.test.mongodb.url", matches = "jdbc:mongodb://127[.]0[.]0[.]1:[0-9]+/admin\\?authSource=admin")
@EnabledIfSystemProperty(named = "datahub.test.mongodb.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_MONGODB_TEST_PASSWORD", matches = ".+")
@Timeout(120)
class MongoMigrationMongoTest {
    private static ConfiguredJdbcDrivers drivers;
    private DbSwitchRuntime runtime;
    private String schema;
    @BeforeAll static void bundle() throws Exception {
        var jar = Path.of(System.getProperty("datahub.test.mongodb.driver")).toRealPath();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(jar)) { byte[] buffer = new byte[8192]; int count; while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count); }
        var config = new DbSwitchProperties.Driver(); config.setPath(jar.toString()); config.setSha256(HexFormat.of().formatHex(digest.digest()));
        drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.MONGODB, config));
    }
    @AfterAll static void closeBundle() { if (drivers != null) drivers.close(); }
    @BeforeEach void setup() throws Exception {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(),
                new DbSwitchOptions(32L * 1024 * 1024, true, false, 15, 0, 300_000, 30_000, 0), drivers);
        schema = "native_" + UUID.randomUUID().toString().replace("-", "");
        command("{\"create\":\"source\"}"); command("{\"create\":\"target\"}");
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null && schema != null) command("{\"dropDatabase\":1}"); }
        finally { if (runtime != null) runtime.close(); }
        assertEquals(0, drivers.runtimes().connections().snapshot().active());
    }

    @Test void nativeFramePreservesObjectIdDecimal128DateNestedValuesAndMissingAcrossFullMigration() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        writer.prepareWrite(schema, "source", MongoMigrationFrame.FIELDS);
        List<Object[]> frames = new ArrayList<>();
        try (var connection = source().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            for (int i = 0; i < 503; i++) {
                String id = String.format("%024x", i + 1);
                String optional = i == 0 ? ",\"optional\":null" : i == 502 ? ",\"lateField\":\"not in first sample\"" : "";
                byte[] document = session.encode("{\"_id\":{\"$oid\":\"" + id + "\"},\"ordinal\":{\"$numberInt\":\"" + i
                        + "\"},\"amount\":{\"$numberDecimal\":\"12345678901234567890.12345678901234\"},"
                        + "\"at\":{\"$date\":{\"$numberLong\":\"1767225600123\"}},\"nested\":{\"arr\":[1,null,{\"x\":\"中文\"}]},"
                        + "\"binary\":{\"$binary\":{\"base64\":\"AAECAw==\",\"subType\":\"00\"}}" + optional + "}");
                frames.add(MongoMigrationFrame.detach(session, document));
            }
        }
        assertEquals(503, writer.write(MongoMigrationFrame.FIELDS, frames));
        var reader = runtime.reader(source(), ProductTypeEnum.MONGODB); reader.setFetchSize(37);
        try (var result = reader.queryTableData(schema, "source", List.of("optional", "ordinal"), List.of("ordinal"))) {
            var rows = result.getResultset(); assertTrue(rows.next());
            assertInstanceOf(NativeCellValue.class, rows.getObject(1)); assertFalse(rows.wasNull());
            assertTrue(rows.next()); assertSame(MissingCellValue.INSTANCE, rows.getObject(1)); assertFalse(rows.wasNull());
        }
        assertTrue(new MigrationService(migration(false), ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        List<Object[]> actual = readFrames("target"); assertEquals(503, actual.size());
        assertArrayEquals(frames.getFirst(), actual.getFirst()); assertArrayEquals(frames.getLast(), actual.getLast());
        assertEquals(503, count("target"));
    }

    @Test void differenceReplacesWholeNativeDocumentWithoutLosingMissingNullOrIdentity() throws Exception {
        var sourceWriter = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        var targetWriter = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        sourceWriter.prepareWrite(schema, "source", MongoMigrationFrame.FIELDS); targetWriter.prepareWrite(schema, "target", MongoMigrationFrame.FIELDS);
        List<Object[]> expected = frames("{\"_id\":{\"$numberLong\":\"1\"},\"value\":null}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"amount\":{\"$numberDecimal\":\"0.1234567890123456789012345678901234\"}}",
                "{\"_id\":{\"$numberLong\":\"4\"},\"note\":\"inserted\"}");
        sourceWriter.write(MongoMigrationFrame.FIELDS, expected);
        targetWriter.write(MongoMigrationFrame.FIELDS, frames("{\"_id\":{\"$numberLong\":\"1\"},\"value\":7,\"extra\":\"must disappear\"}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"amount\":{\"$numberDecimal\":\"0.1234567890123456789012345678901234\"}}",
                "{\"_id\":{\"$numberLong\":\"3\"},\"note\":\"delete\"}"));
        assertTrue(new MigrationService(migration(true), ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        List<Object[]> actual = readFrames("target"); assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i));
    }

    @Test void prepareRetainsIdentityAndIndexesAndTruncatePreservesCollectionStructure() throws Exception {
        command("{\"createIndexes\":\"target\",\"indexes\":[{\"key\":{\"value\":1},\"name\":\"value_idx\"}]}");
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        writer.prepareWrite(schema, "target", List.of("_id", "value")); writer.write(List.of("_id", "value"), List.<Object[]>of(new Object[]{1L, "keep"}));
        var before = collectionInfo("target");
        writer.prepareWrite(schema, "target", List.of("_id", "value")); assertEquals(1, count("target"));
        var reader = runtime.reader(source(), ProductTypeEnum.MONGODB); reader.truncateTableData(schema, "target");
        assertEquals(0, count("target")); assertEquals(before, collectionInfo("target"));
        assertEquals(2, command("{\"listIndexes\":\"target\",\"cursor\":{\"batchSize\":65}}").path("cursor").path("firstBatch").size());
        reader.dropTable(schema, "target");
        assertEquals(0, command("{\"listCollections\":1,\"filter\":{\"name\":\"target\"}}").path("cursor").path("firstBatch").size());
    }

    @Test void partialNativeInsertReportsAcceptedRejectedAndNeverOverwritesExistingIdentity() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT); writer.prepareWrite(schema, "target", List.of("_id", "value"));
        writer.write(List.of("_id", "value"), List.<Object[]>of(new Object[]{1L, "original"}));
        var partial = assertThrows(PartialBatchWriteException.class, () -> writer.write(List.of("_id", "value"),
                List.of(new Object[]{1L, "no overwrite"}, new Object[]{2L, "accepted"})));
        assertEquals(1, partial.acknowledgedRows()); assertEquals(1, partial.rejectedRows());
        assertEquals(0, partial.unknownRows()); assertTrue(partial.terminationConfirmed()); assertEquals(2, count("target"));
        var first = command("{\"find\":\"target\",\"filter\":{\"_id\":{\"$numberLong\":\"1\"}},\"limit\":1}")
                .path("cursor").path("firstBatch").get(0);
        assertEquals("original", first.path("value").textValue());
    }

    @Test void forgedOrInconsistentFrameAndLateInvalidValueCannotWriteEarlierRows() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT); writer.prepareWrite(schema, "target", MongoMigrationFrame.FIELDS);
        var original = frames("{\"_id\":{\"$numberLong\":\"1\"},\"value\":true}").getFirst();
        var id = (NativeCellValue) original[0]; var forged = original.clone();
        forged[0] = new NativeCellValue(id.format(), id.encoded(), new byte[]{99});
        assertThrows(IllegalArgumentException.class, () -> writer.write(MongoMigrationFrame.FIELDS, List.of(original, forged)));
        var other = frames("{\"_id\":{\"$numberLong\":\"2\"}}").getFirst();
        assertThrows(IllegalArgumentException.class, () -> writer.write(MongoMigrationFrame.FIELDS,
                List.<Object[]>of(new Object[]{other[0], original[1]})));
        writer.prepareWrite(schema, "target", List.of("_id", "at"));
        assertThrows(IllegalArgumentException.class, () -> writer.write(List.of("_id", "at"), List.of(
                new Object[]{1L, java.time.Instant.ofEpochMilli(1)}, new Object[]{2L, java.time.Instant.ofEpochSecond(1, 1)})));
        assertEquals(0, count("target"));
    }

    @Test void numericIdentityContractMatchesMongoUniquenessForIntegersAndDecimal128() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT); writer.prepareWrite(schema, "target", List.of("_id"));
        writer.write(List.of("_id"), List.<Object[]>of(new Object[]{1}));
        for (Object equivalent : List.of(1L, new java.math.BigDecimal("1.00"), 1.0D)) {
            var rejected = assertThrows(PartialBatchWriteException.class,
                    () -> writer.write(List.of("_id"), List.<Object[]>of(new Object[]{equivalent})));
            assertEquals(1, rejected.rejectedRows()); assertEquals(0, rejected.acknowledgedRows());
        }
        try (var connection = source().getConnection()) {
            var session = MongoNativeSession.open(connection, 1024 * 1024, 5);
            var first = MongoCellCodec.detach(session, MongoCellCodec.encode(session, 1, true), true);
            for (Object equivalent : List.of(1L, new java.math.BigDecimal("1.00"), 1.0D))
                assertEquals(0, first.compareMatchingKey(MongoCellCodec.detach(session, MongoCellCodec.encode(session, equivalent, true), true)));
            for (double unproved : new double[]{0.1D, Double.POSITIVE_INFINITY}) {
                var nativeId = MongoCellCodec.detach(session, MongoCellCodec.encode(session, unproved, true), true);
                assertFalse(nativeId.hasMatchingKey());
                assertThrows(IllegalArgumentException.class, () -> nativeId.compareMatchingKey(nativeId));
            }
        }
        assertEquals(1, count("target"));
    }

    @Test void fullTransferKeepsComplexFloatingAndNullIdsWhileDiffRejectsUnprovedKeysBeforeWrite() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        writer.prepareWrite(schema, "source", MongoMigrationFrame.FIELDS);
        var expected = frames("{\"_id\":{\"partition\":7,\"name\":\"complex\"},\"value\":{\"$numberDecimal\":\"NaN\"}}",
                "{\"_id\":{\"$numberDouble\":\"0.1\"},\"value\":{\"$numberDecimal\":\"-0\"}}",
                "{\"_id\":null,\"value\":\"legal BSON null identity\"}");
        for (var row : expected) assertFalse(((NativeCellValue) row[0]).hasMatchingKey());
        assertEquals(3, writer.write(MongoMigrationFrame.FIELDS, expected));
        assertTrue(new MigrationService(migration(false), ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        var actual = readFrames("target"); assertEquals(3, actual.size());
        assertEquals(new HashSet<>(expected.stream().map(row -> row[1]).toList()),
                new HashSet<>(actual.stream().map(row -> row[1]).toList()));
        assertFalse(new MigrationService(migration(true), ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(new HashSet<>(actual.stream().map(row -> row[1]).toList()),
                new HashSet<>(readFrames("target").stream().map(row -> row[1]).toList()));
    }

    @Test void staleCollectionAndUnsafeReadHandlesCannotBeReused() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT); writer.prepareWrite(schema, "target", List.of("_id"));
        command("{\"drop\":\"target\"}"); command("{\"create\":\"target\"}");
        assertThrows(IllegalStateException.class, () -> writer.write(List.of("_id"), List.<Object[]>of(new Object[]{1L})));
        assertEquals(0, count("target"));
        var reader = runtime.reader(source(), ProductTypeEnum.MONGODB);
        assertThrows(IllegalArgumentException.class, () -> reader.setFetchSize(0));
        assertThrows(IllegalArgumentException.class, () -> reader.queryTableData("db.getCollection('target').find()"));
        assertThrows(IllegalArgumentException.class, () -> reader.queryTableData(schema, "target');drop()", List.of("_id")));
    }

    @Test void migrationCreatesAndReplacesNativeCollectionWithValidatorAndCompoundUniqueIndex() throws Exception {
        command("{\"collMod\":\"source\",\"validator\":{\"$jsonSchema\":{\"bsonType\":\"object\",\"required\":[\"amount\"],"
                + "\"properties\":{\"amount\":{\"bsonType\":\"decimal\"}}}},\"validationLevel\":\"strict\",\"validationAction\":\"error\"}");
        command("{\"createIndexes\":\"source\",\"indexes\":[{\"key\":{\"group\":1,\"amount\":-1},\"name\":\"group_amount\",\"unique\":true}]}");
        var expected = frames("{\"_id\":1,\"group\":\"one\",\"amount\":{\"$numberDecimal\":\"0.1234567890123456789012345678901234\"}}");
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        writer.prepareWrite(schema, "source", MongoMigrationFrame.FIELDS); writer.write(MongoMigrationFrame.FIELDS, expected);
        command("{\"drop\":\"target\"}");
        assertTrue(new MigrationService(migration(false), ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertArrayEquals(expected.getFirst(), readFrames("target").getFirst());
        assertEquals(collectionInfo("source").path("options"), collectionInfo("target").path("options"));
        var sourceIndexes = command("{\"listIndexes\":\"source\",\"cursor\":{\"batchSize\":65}}").path("cursor").path("firstBatch");
        assertEquals(sourceIndexes, command("{\"listIndexes\":\"target\",\"cursor\":{\"batchSize\":65}}").path("cursor").path("firstBatch"));
        var before = collectionInfo("target").path("info").path("uuid");
        var request = migration(false); request.getTarget().setTargetDrop(true); request.getTarget().setExclusiveTargetDdlOwnership(true);
        var replaced = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(replaced.getIfAllSuccess().get());
        var recovery = replaced.getTableResultList().getFirst();
        assertTrue(recovery.getNativePlanDigest().matches("[a-f0-9]{64}"));
        assertEquals(2, recovery.getNativeRecoveryResources().size());
        assertTrue(recovery.getNativeRecoveryResources().stream().allMatch(resource -> resource.expectedPhysicalIdentity() != null));
        assertNotEquals(before, collectionInfo("target").path("info").path("uuid"));
        assertEquals(1, count("target")); assertArrayEquals(expected.getFirst(), readFrames("target").getFirst());
        assertEquals(2, command("{\"listCollections\":1,\"nameOnly\":true}").path("cursor").path("firstBatch").size());
    }

    @Test void replacementWithoutExplicitDdlOwnershipPreservesTargetAndCapturesPlanBeforeRefusal() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        writer.prepareWrite(schema, "target", List.of("_id")); writer.write(List.of("_id"), List.<Object[]>of(new Object[]{7}));
        var before = collectionInfo("target");
        var request = migration(false); request.getTarget().setTargetDrop(true);
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertFalse(result.getIfAllSuccess().get());
        var rejected = result.getTableResultList().getFirst();
        assertNotNull(rejected.getNativePlanDigest()); assertEquals(2, rejected.getNativeRecoveryResources().size());
        assertEquals(before, collectionInfo("target")); assertEquals(1, count("target"));
        assertEquals(2, command("{\"listCollections\":1,\"nameOnly\":true}").path("cursor").path("firstBatch").size());
    }

    @Test void nativeOnlyCreateRetainsStructureWithoutMovingRowsAndPlanIsSingleUse() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        writer.prepareWrite(schema, "source", List.of("_id")); writer.write(List.of("_id"), List.<Object[]>of(new Object[]{1}));
        command("{\"drop\":\"target\"}");
        var request = migration(false); request.getTarget().setOnlyCreate(true);
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(0, count("target"));
        var operator = runtime.reader(source(), ProductTypeEnum.MONGODB);
        var definition = operator.describeNativeTable(schema, "source");
        var plan = operator.prepareNativeTarget(definition, schema, "target", frameMapping(), false);
        assertFalse(plan.replacesExisting()); plan.execute();
        assertThrows(IllegalStateException.class, plan::execute);
    }

    @Test void invalidNativeDefinitionFieldMappingAndStaleIdentityCannotReplaceExistingData() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.MONGODB, WriteMode.INSERT);
        writer.prepareWrite(schema, "target", List.of("_id")); writer.write(List.of("_id"), List.<Object[]>of(new Object[]{7}));
        var operator = runtime.reader(source(), ProductTypeEnum.MONGODB);
        var definition = operator.describeNativeTable(schema, "source");
        assertThrows(IllegalArgumentException.class, () -> operator.prepareNativeTarget(definition, schema, "target",
                Map.of("_id", "_id", MongoMigrationFrame.DOCUMENT, "renamed"), true));
        var invalid = new com.fasterxml.jackson.databind.ObjectMapper().readTree(definition.document());
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("options")).put("capped", true);
        var badDefinition = new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(
                definition.product(), definition.format(), invalid.toString(), definition.fields());
        assertThrows(IllegalArgumentException.class, () -> operator.prepareNativeTarget(badDefinition, schema, "target", frameMapping(), true));
        assertEquals(1, count("target"));
        var plan = operator.prepareNativeTarget(definition, schema, "target", frameMapping(), true);
        command("{\"drop\":\"target\"}"); command("{\"create\":\"target\"}");
        writer.prepareWrite(schema, "target", List.of("_id")); writer.write(List.of("_id"), List.<Object[]>of(new Object[]{99}));
        assertThrows(IllegalStateException.class, plan::execute);
        assertEquals(1, count("target"));
        assertEquals(2, command("{\"listCollections\":1,\"nameOnly\":true}").path("cursor").path("firstBatch").size());
    }

    private static Map<String, String> frameMapping() {
        return Map.of("_id", "_id", MongoMigrationFrame.DOCUMENT, MongoMigrationFrame.DOCUMENT);
    }

    @Test void compoundIndexOrderIsPartOfNativeStructureEvidence() throws Exception {
        command("{\"createIndexes\":\"source\",\"indexes\":[{\"key\":{\"first\":1,\"second\":1},\"name\":\"compound\"}]}");
        command("{\"createIndexes\":\"target\",\"indexes\":[{\"key\":{\"second\":1,\"first\":1},\"name\":\"compound\"}]}");
        var operator = runtime.reader(source(), ProductTypeEnum.MONGODB);
        var definition = operator.describeNativeTable(schema, "source");
        var before = collectionInfo("target");
        assertThrows(IllegalStateException.class, () -> operator.prepareNativeTarget(definition, schema, "target", frameMapping(), false));
        assertEquals(before, collectionInfo("target"));
    }

    private List<Object[]> frames(String... documents) throws Exception {
        List<Object[]> frames = new ArrayList<>();
        try (var connection = source().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            for (String document : documents) frames.add(MongoMigrationFrame.detach(session, session.encode(document)));
        }
        return frames;
    }
    private List<Object[]> readFrames(String collection) throws Exception {
        List<Object[]> frames = new ArrayList<>(); var reader = runtime.reader(source(), ProductTypeEnum.MONGODB); reader.setFetchSize(31);
        try (var result = reader.queryTableData(schema, collection, MongoMigrationFrame.FIELDS, List.of("_id"))) {
            var rs = result.getResultset(); while (rs.next()) frames.add(new Object[]{rs.getObject(1), rs.getObject(2)});
        }
        return frames;
    }
    private long count(String collection) throws Exception { return MongoReplies.integer(command("{\"count\":\"" + collection + "\"}").path("n")); }
    private JsonNode collectionInfo(String collection) throws Exception { return command("{\"listCollections\":1,\"filter\":{\"name\":\"" + collection + "\"}}").path("cursor").path("firstBatch").get(0); }
    private JsonNode command(String command) throws Exception {
        try (var connection = source().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            return MongoReplies.success(session, session.command(schema, session.encode(command)));
        }
    }
    private AbstractDataSource source() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                return runtime.openConnection(ProductTypeEnum.MONGODB, null, System.getProperty("datahub.test.mongodb.url"),
                        "q11root", System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Fixture credentials are fixed"); }
        };
    }
    private MigrationRequest migration(boolean diff) {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties(); source.setSourceProductType(ProductTypeEnum.MONGODB);
        source.setUrl(System.getProperty("datahub.test.mongodb.url")); source.setUsername("q11root"); source.setPassword(System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
        source.setDriverClassName(ProductTypeEnum.MONGODB.getDriveClassName()); source.setSourceSchema(schema); source.setSourceIncludes("source");
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "target"))); source.setPoolSize(1); source.setFetchSize(37); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.MONGODB); target.setUrl(System.getProperty("datahub.test.mongodb.url"));
        target.setUsername("q11root"); target.setPassword(System.getenv("DATAHUB_MONGODB_TEST_PASSWORD")); target.setDriverClassName(ProductTypeEnum.MONGODB.getDriveClassName());
        target.setTargetSchema(schema); target.setTargetTable("target"); target.setPoolSize(1); target.setChangeDataSync(diff); target.setChangeDataSyncType("PK_ORDER_COMPARE");
        return request;
    }
}
