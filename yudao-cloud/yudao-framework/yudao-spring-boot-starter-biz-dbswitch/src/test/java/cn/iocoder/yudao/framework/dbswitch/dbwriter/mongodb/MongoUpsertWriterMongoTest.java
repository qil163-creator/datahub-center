package cn.iocoder.yudao.framework.dbswitch.dbwriter.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoMigrationFrame;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB 7 native _id upsert: single unique key, typed identity match, exact receipts, no silent replacement. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.mongodb.url", matches = "jdbc:mongodb://127[.]0[.]0[.]1:[0-9]+/admin\\?authSource=admin")
@EnabledIfSystemProperty(named = "datahub.test.mongodb.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_MONGODB_TEST_PASSWORD", matches = ".+")
class MongoUpsertWriterMongoTest {
    private static final ProductTypeEnum MONGO = ProductTypeEnum.MONGODB;
    private static final BatchLimits LIMITS = new BatchLimits(2, 262144, 131072, 65535, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(32L * 1024 * 1024, true, false, 15, 0, 300_000, 30_000, 0);
    private static ConfiguredJdbcDrivers drivers;
    private DbSwitchRuntime runtime;
    private String schema;

    @BeforeAll static void bundle() throws Exception {
        var jar = Path.of(System.getProperty("datahub.test.mongodb.driver")).toRealPath();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(jar)) { byte[] buffer = new byte[8192]; int count; while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count); }
        var config = new DbSwitchProperties.Driver(); config.setPath(jar.toString()); config.setSha256(HexFormat.of().formatHex(digest.digest()));
        drivers = new ConfiguredJdbcDrivers(Map.of(MONGO, config));
    }
    @AfterAll static void closeBundle() { if (drivers != null) drivers.close(); }
    @BeforeEach void setup() throws Exception {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), OPTIONS, drivers);
        schema = "mongo_upsert_" + UUID.randomUUID().toString().replace("-", "");
        command("{\"create\":\"t\"}");
    }
    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null && schema != null) command("{\"dropDatabase\":1}"); }
        finally { if (runtime != null) runtime.close(); }
        assertEquals(0, drivers.runtimes().connections().snapshot().active());
    }

    @Test void frameUpsertAcknowledgesInputRowsAndReplaysIdempotently() throws Exception {
        var writer = runtime.upsertWriter(ds(), MONGO);
        writer.configureBatchLimits(LIMITS);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema, "t", MongoMigrationFrame.FIELDS, List.of("k")));
        writer.prepareUpsert(schema, "t", MongoMigrationFrame.FIELDS, List.of("_id"));
        assertThrows(IllegalArgumentException.class, () -> writer.prepareWrite(schema, "t", MongoMigrationFrame.FIELDS));
        var receipt = writer.writeWithReceipt(MongoMigrationFrame.FIELDS, frames("{\"_id\":{\"$numberLong\":\"1\"},\"v\":\"a\"}",
                "{\"_id\":{\"$numberLong\":\"2\"},\"v\":\"b\"}"));
        assertEquals(2, receipt.acknowledgedRows()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, receipt.commitAcknowledgement());
        assertNull(receipt.receipt());
        assertEquals(2, writer.writeWithReceipt(MongoMigrationFrame.FIELDS, frames("{\"_id\":{\"$numberLong\":\"1\"},\"v\":\"changed\"}",
                "{\"_id\":{\"$numberLong\":\"3\"},\"v\":\"c\"}")).acknowledgedRows());
        assertEquals(2, writer.writeWithReceipt(MongoMigrationFrame.FIELDS, frames("{\"_id\":{\"$numberLong\":\"1\"},\"v\":\"changed\"}",
                "{\"_id\":{\"$numberLong\":\"3\"},\"v\":\"c\"}")).acknowledgedRows());
        assertEquals(List.of(canonical("{\"_id\":{\"$numberLong\":\"1\"},\"v\":\"changed\"}"),
                canonical("{\"_id\":{\"$numberLong\":\"2\"},\"v\":\"b\"}"), canonical("{\"_id\":{\"$numberLong\":\"3\"},\"v\":\"c\"}")), documents("t"));
    }

    @Test void typedIdentityNeverSilentlyReplacesADifferentNumericType() throws Exception {
        insert("{\"_id\":{\"$numberLong\":\"5\"},\"v\":\"long doc\"}");
        var writer = runtime.upsertWriter(ds(), MONGO); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(schema, "t", List.of("_id", "v"), List.of("_id"));
        // The default _id index compares numeric types by value, so an int32 5 cannot coexist with the stored long 5:
        // the typed upsert must fail loudly instead of silently replacing the different-typed identity.
        assertThrows(PartialBatchWriteException.class, () -> writer.writeWithReceipt(List.of("_id", "v"),
                List.<Object[]>of(new Object[]{5, "int doc"})));
        assertEquals(1, count("t"), "The long identity is untouched and no numeric-equal twin was created");
        assertEquals(List.of(canonical("{\"_id\":{\"$numberLong\":\"5\"},\"v\":\"long doc\"}")), documents("t"));
    }

    @Test void fieldModeUpsertMergesAndUnsetsWithoutReplacingTheDocument() throws Exception {
        insert("{\"_id\":{\"$numberLong\":\"1\"},\"v\":\"old\",\"keep\":\"k\"}", "{\"_id\":{\"$numberLong\":\"3\"},\"v\":\"x\"}");
        var writer = runtime.upsertWriter(ds(), MONGO); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(schema, "t", List.of("_id", "v"), List.of("_id"));
        assertEquals(2, writer.writeWithReceipt(List.of("_id", "v"), List.of(new Object[]{1L, "changed"}, new Object[]{3L, MissingCellValue.INSTANCE})).acknowledgedRows());
        assertEquals(List.of(canonical("{\"_id\":{\"$numberLong\":\"1\"},\"keep\":\"k\",\"v\":\"changed\"}"), canonical("{\"_id\":{\"$numberLong\":\"3\"}}")),
                documents("t"));
    }

    @Test void frameIdentityDisagreementIsRejectedBeforeAnyWrite() throws Exception {
        var writer = runtime.upsertWriter(ds(), MONGO); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(schema, "t", MongoMigrationFrame.FIELDS, List.of("_id"));
        try (var connection = ds().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            var a = MongoMigrationFrame.detach(session, session.encode("{\"_id\":{\"$numberLong\":\"1\"},\"v\":\"a\"}"));
            var b = MongoMigrationFrame.detach(session, session.encode("{\"_id\":{\"$numberLong\":\"2\"},\"v\":\"b\"}"));
            assertThrows(IllegalArgumentException.class, () -> writer.writeWithReceipt(MongoMigrationFrame.FIELDS,
                    List.<Object[]>of(new Object[]{a[0], b[1]})));
        }
        assertEquals(0, count("t"));
    }

    @Test void secondUniqueIndexIsRefusedAtPrepareAndAPlainIndexIsAllowed() throws Exception {
        command("{\"createIndexes\":\"t\",\"indexes\":[{\"key\":{\"k\":1},\"name\":\"k_1\",\"unique\":true}]}");
        var writer = runtime.upsertWriter(ds(), MONGO); writer.configureBatchLimits(LIMITS);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema, "t", List.of("_id", "k"), List.of("_id")));
        assertThrows(RuntimeException.class, () -> writer.write(List.of("_id", "k"), List.<Object[]>of(new Object[]{1L, 1})));
        command("{\"dropIndexes\":\"t\",\"index\":\"k_1\"}");
        command("{\"createIndexes\":\"t\",\"indexes\":[{\"key\":{\"k\":1},\"name\":\"k_1\"}]}");
        writer.prepareUpsert(schema, "t", List.of("_id", "k"), List.of("_id"));
        assertEquals(1, writer.writeWithReceipt(List.of("_id", "k"), List.<Object[]>of(new Object[]{1L, 1})).acknowledgedRows());
        assertEquals(1, count("t"));
    }

    @Test void duplicateIdentitiesInOneBatchAreRejectedBeforeAnyWrite() throws Exception {
        var writer = runtime.upsertWriter(ds(), MONGO); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(schema, "t", List.of("_id", "v"), List.of("_id"));
        assertThrows(IllegalArgumentException.class, () -> writer.writeWithReceipt(List.of("_id", "v"),
                List.of(new Object[]{1L, "a"}, new Object[]{1L, "b"})));
        assertEquals(0, count("t"));
    }

    @Test void serverConstraintFailureIsPartialAndNeverSilentlySuccessful() throws Exception {
        command("{\"drop\":\"t\"}"); command("{\"create\":\"t\",\"validator\":{\"v\":{\"$gte\":0}},\"validationLevel\":\"strict\"}");
        var writer = runtime.upsertWriter(ds(), MONGO); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(schema, "t", List.of("_id", "v"), List.of("_id"));
        assertThrows(PartialBatchWriteException.class, () -> writer.writeWithReceipt(List.of("_id", "v"),
                List.of(new Object[]{1L, 1}, new Object[]{2L, -1})));
        assertEquals(List.of(canonical("{\"_id\":{\"$numberLong\":\"1\"},\"v\":{\"$numberInt\":\"1\"}}")), documents("t"));
    }

    @Test void keyContractIsTheNativeIdOnlyDomainAndReportsTheExactReceipt() throws Exception {
        var writer = runtime.upsertWriter(ds(), MONGO); writer.configureBatchLimits(LIMITS);
        writer.prepareUpsert(schema, "t", List.of("_id", "v"), List.of("_id"));
        var contract = writer.keyContract();
        assertEquals(MONGO, contract.product()); assertEquals(1, contract.keys().size());
        assertNull(contract.keys().getFirst().numeric()); assertTrue(contract.nativeFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(writer.targetSchemaFingerprint().matches("[0-9a-f]{64}"));
        assertEquals(writer.keyContract().nativeFingerprint(), writer.keyContractFingerprint());
    }

    // ---- fixtures ---------------------------------------------------------------------------------------

    private JsonNode canonical(String json) throws Exception {
        try (var connection = ds().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(session.canonicalJson(session.encode(json)));
        }
    }
    private List<Object[]> frames(String... documents) throws Exception {
        List<Object[]> frames = new ArrayList<>();
        try (var connection = ds().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            for (String document : documents) frames.add(MongoMigrationFrame.detach(session, session.encode(document)));
        }
        return frames;
    }
    private void insert(String... documents) throws Exception {
        var writer = runtime.writer(ds(), MONGO, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT);
        writer.prepareWrite(schema, "t", MongoMigrationFrame.FIELDS);
        assertEquals(documents.length, writer.write(MongoMigrationFrame.FIELDS, frames(documents)));
    }
    private List<JsonNode> documents(String collection) throws Exception {
        var reader = runtime.reader(ds(), MONGO); reader.setFetchSize(31);
        var docs = new ArrayList<JsonNode>();
        try (var result = reader.queryTableData(schema, collection, MongoMigrationFrame.FIELDS, List.of("_id"))) {
            var rows = result.getResultset();
            try (var connection = ds().getConnection()) {
                var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
                while (rows.next()) docs.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(session.canonicalJson(
                        MongoMigrationFrame.document(session, new Object[]{rows.getObject(1), rows.getObject(2)}))));
            }
        }
        docs.sort(Comparator.comparing(Object::toString)); return docs;
    }
    private long count(String collection) throws Exception { return MongoReplies.integer(command("{\"count\":\"" + collection + "\"}").path("n")); }
    private JsonNode command(String command) throws Exception {
        try (var connection = ds().getConnection()) {
            var session = MongoNativeSession.open(connection, 32L * 1024 * 1024, 15);
            return MongoReplies.success(session, session.command(schema, session.encode(command)));
        }
    }
    private DataSource ds() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                return runtime.openConnection(MONGO, null, System.getProperty("datahub.test.mongodb.url"),
                        "q11root", System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }
}
