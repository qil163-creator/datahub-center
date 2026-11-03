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
class MongoNativeSchemaMongoTest {
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

    private static Map<String,String> frameMapping(){return Map.of("_id","_id",MongoMigrationFrame.DOCUMENT,MongoMigrationFrame.DOCUMENT);}

    @Test void nativeOptionsAndAllIndexKindsCloneWithoutLosingStructure()throws Exception {
        command("{\"drop\":\"source\"}");
        command("{\"create\":\"source\",\"collation\":{\"locale\":\"en\",\"strength\":2},\"changeStreamPreAndPostImages\":{\"enabled\":true}}");
        command("""
                {"createIndexes":"source","indexes":[
                {"key":{"hash":"hashed"},"name":"hash_idx"},
                {"key":{"position":"2dsphere"},"name":"sphere_idx"},
                {"key":{"plane":"2d"},"name":"plane_idx","min":-180,"max":180,"bits":26,"collation":{"locale":"simple"}},
                {"key":{"nested.$**":1},"name":"wildcard_idx"},
                {"key":{"title":"text"},"name":"text_idx","collation":{"locale":"simple"},"weights":{"title":5},"default_language":"english"},
                {"key":{"中文":1},"name":"unicode_idx","hidden":true},
                {"key":{"expires":1},"name":"ttl_idx","expireAfterSeconds":3600},
                {"key":{"code":1,"version":-1},"name":"partial_unique_idx","unique":true,"partialFilterExpression":{"active":true}}]}
                """);
        var operator=runtime.reader(source(),ProductTypeEnum.MONGODB);var desired=operator.describeNativeTable(schema,"source");
        var before=collectionInfo("target");var plan=operator.prepareNativeTarget(desired,schema,"target",frameMapping(),true);
        assertEquals(before,collectionInfo("target"));plan.execute();
        assertEquals(collectionInfo("source").path("options").toString(),collectionInfo("target").path("options").toString());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(json.readTree(desired.document()).path("indexes").toString(),json.readTree(operator.describeNativeTable(schema,"target").document()).path("indexes").toString());
        assertEquals(9,command("{\"listIndexes\":\"target\",\"cursor\":{\"batchSize\":65}}").path("cursor").path("firstBatch").size());
    }
    @Test void cappedAndClusteredCollectionsRetainOptionsThroughPublicOnlyCreate()throws Exception {
        for(String options:List.of("\"capped\":true,\"size\":4096,\"max\":5","\"clusteredIndex\":{\"key\":{\"_id\":1},\"unique\":true}")) {
            command("{\"drop\":\"source\"}");command("{\"drop\":\"target\"}");command("{\"create\":\"source\","+options+"}");
            command("{\"insert\":\"source\",\"documents\":[{\"_id\":1,\"value\":\"keep\"}]}");
            var request=migration(false);request.getTarget().setOnlyCreate(true);
            assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
            assertEquals(collectionInfo("source").path("options").toString(),collectionInfo("target").path("options").toString());
            assertEquals(0,count("target"));assertEquals(1,count("source"));
        }
    }
    @Test void nativeEvolutionPreservesUuidRowsAndAppliesValidatorAndIndex()throws Exception {
        command("{\"insert\":\"target\",\"documents\":[{\"_id\":1,\"value\":\"keep\"}]}");
        command("{\"collMod\":\"source\",\"validator\":{\"$jsonSchema\":{\"bsonType\":\"object\",\"required\":[\"value\"]}},\"validationLevel\":\"strict\",\"validationAction\":\"error\"}");
        command("{\"createIndexes\":\"source\",\"indexes\":[{\"key\":{\"value\":1},\"name\":\"value_idx\",\"unique\":true}]}");
        var before=collectionInfo("target").path("info").path("uuid");var rows=readFrames("target");
        var operator=runtime.reader(source(),ProductTypeEnum.MONGODB);var desired=operator.describeNativeTable(schema,"source");
        var plan=operator.prepareNativeSchemaEvolution(desired,schema,"target");assertTrue(plan.requiresExclusiveDdlOwnership());assertFalse(plan.replacesExisting());
        plan.execute();assertEquals(before,collectionInfo("target").path("info").path("uuid"));assertArrayEquals(rows.getFirst(),readFrames("target").getFirst());
        assertEquals(collectionInfo("source").path("options"),collectionInfo("target").path("options"));
        assertThrows(IllegalStateException.class,plan::execute);operator.prepareNativeSchemaEvolution(desired,schema,"target").execute();
        assertTrue(command("{\"insert\":\"target\",\"documents\":[{\"_id\":2}]}").has("writeErrors"));assertEquals(1,count("target"));
    }
    @Test void indexRemovalOrRebuildRejectsBeforeChangingTargetRows()throws Exception {
        command("{\"insert\":\"target\",\"documents\":[{\"_id\":1,\"value\":\"keep\"}]}");
        command("{\"createIndexes\":\"target\",\"indexes\":[{\"key\":{\"value\":1},\"name\":\"value_idx\"}]}");
        var operator=runtime.reader(source(),ProductTypeEnum.MONGODB);var before=collectionInfo("target");
        var desired=operator.describeNativeTable(schema,"source");
        assertThrows(IllegalArgumentException.class,()->operator.prepareNativeSchemaEvolution(desired,schema,"target"));
        command("{\"createIndexes\":\"source\",\"indexes\":[{\"key\":{\"value\":-1},\"name\":\"value_idx\"}]}");
        var changed=operator.describeNativeTable(schema,"source");assertThrows(IllegalArgumentException.class,()->operator.prepareNativeSchemaEvolution(changed,schema,"target"));
        assertEquals(before,collectionInfo("target"));assertEquals(1,count("target"));
    }
    @Test void recreatedCollectionCannotRunEvolutionOrReplay()throws Exception {
        var operator=runtime.reader(source(),ProductTypeEnum.MONGODB);var desired=operator.describeNativeTable(schema,"source");
        var plan=operator.prepareNativeSchemaEvolution(desired,schema,"target");command("{\"drop\":\"target\"}");command("{\"create\":\"target\"}");
        command("{\"insert\":\"target\",\"documents\":[{\"_id\":99,\"value\":\"sentinel\"}]}");
        assertThrows(IllegalStateException.class,plan::execute);assertEquals(1,count("target"));assertThrows(IllegalStateException.class,plan::execute);
    }

    @Test void timeSeriesNativeCreationKeepsOptionsIndexesAndExplicitBucketIdentity()throws Exception {
        command("{\"drop\":\"source\"}");command("{\"drop\":\"target\"}");
        command("{\"create\":\"source\",\"timeseries\":{\"timeField\":\"time\",\"metaField\":\"host\",\"granularity\":\"seconds\"},\"expireAfterSeconds\":3600}");
        command("{\"createIndexes\":\"source\",\"indexes\":[{\"key\":{\"temperature\":1},\"name\":\"temperature_idx\"}]}");
        command("{\"insert\":\"source\",\"documents\":[{\"time\":{\"$date\":{\"$numberLong\":\""+System.currentTimeMillis()+"\"}},\"host\":\"host1\",\"temperature\":20}]}");
        var operator=runtime.reader(source(),ProductTypeEnum.MONGODB);var definition=operator.describeNativeTable(schema,"source");
        assertEquals("mongodb-special-structure-v1",definition.format());var plan=operator.prepareNativeTarget(definition,schema,"target",frameMapping(),false);
        assertTrue(plan.requiresExclusiveDdlOwnership());assertThrows(UnsupportedOperationException.class,plan::preflightDataTransfer);
        plan.execute();assertEquals(collectionInfo("source").path("options").toString(),collectionInfo("target").path("options").toString());
        assertEquals(2,plan.recoveryResources().size());assertNotNull(plan.recoveryResources().getLast().expectedPhysicalIdentity());
        assertEquals(0,count("target"));assertEquals(1,count("source"));
        assertThrows(IllegalArgumentException.class,()->operator.prepareNativeTarget(definition,schema,"target",frameMapping(),true));
        assertEquals(0,count("target"));
    }
    @Test void nativeViewCreationBindsExistingDependencyAndPreservesBaseRows()throws Exception {
        command("{\"insert\":\"source\",\"documents\":[{\"_id\":1,\"active\":true},{\"_id\":2,\"active\":false}]}");
        command("{\"create\":\"source_view\",\"viewOn\":\"source\",\"pipeline\":[{\"$match\":{\"active\":true}}]}");
        var operator=runtime.reader(source(),ProductTypeEnum.MONGODB);var definition=operator.describeNativeTable(schema,"source_view");
        var plan=operator.prepareNativeTarget(definition,schema,"target_view",frameMapping(),false);assertTrue(plan.requiresExclusiveDdlOwnership());
        assertEquals("source",plan.recoveryResources().getLast().name());assertNotNull(plan.recoveryResources().getLast().expectedPhysicalIdentity());
        assertThrows(UnsupportedOperationException.class,plan::preflightDataTransfer);plan.execute();
        assertEquals(collectionInfo("source_view").path("options"),collectionInfo("target_view").path("options"));
        assertEquals(1,count("target_view"));assertEquals(2,count("source"));assertNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());
    }
    @Test void changedViewDependencyAndForeignPipelineRejectWithoutCreatingTarget()throws Exception {
        command("{\"create\":\"source_view\",\"viewOn\":\"source\",\"pipeline\":[{\"$match\":{\"active\":true}}]}");
        var operator=runtime.reader(source(),ProductTypeEnum.MONGODB);var definition=operator.describeNativeTable(schema,"source_view");
        var plan=operator.prepareNativeTarget(definition,schema,"target_view",frameMapping(),false);
        command("{\"drop\":\"source\"}");command("{\"create\":\"source\"}");assertThrows(IllegalStateException.class,plan::execute);
        assertEquals(0,command("{\"listCollections\":1,\"filter\":{\"name\":\"target_view\"}}").path("cursor").path("firstBatch").size());
        command("{\"create\":\"foreign_view\",\"viewOn\":\"source\",\"pipeline\":[{\"$lookup\":{\"from\":\"target\",\"localField\":\"_id\",\"foreignField\":\"_id\",\"as\":\"other\"}}]}");
        assertThrows(IllegalArgumentException.class,()->operator.describeNativeTable(schema,"foreign_view"));
    }

    @Test void unsupportedCappedDataTransferCannotDropExistingSentinel()throws Exception {
        command("{\"drop\":\"source\"}");command("{\"create\":\"source\",\"capped\":true,\"size\":4096,\"max\":5}");
        command("{\"insert\":\"source\",\"documents\":[{\"_id\":1,\"value\":\"source\"}]}");
        command("{\"insert\":\"target\",\"documents\":[{\"_id\":99,\"value\":\"sentinel\"}]}");
        var before=collectionInfo("target");var rows=readFrames("target");var request=migration(false);
        request.getTarget().setTargetDrop(true);request.getTarget().setExclusiveTargetDdlOwnership(true);
        assertFalse(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
        assertEquals(before,collectionInfo("target"));assertArrayEquals(rows.getFirst(),readFrames("target").getFirst());
        assertEquals(2,command("{\"listCollections\":1,\"nameOnly\":true}").path("cursor").path("firstBatch").size());
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
