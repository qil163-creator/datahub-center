package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual verified JDBC bundle -> native transport -> public runtime and MigrationService. */
@EnabledIfSystemProperty(named = "datahub.test.elasticsearch.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
@Timeout(120)
class ElasticsearchMigrationElasticsearchTest {
    private static ConfiguredJdbcDrivers drivers;
    // Legacy Jest bridge requires credentials even when the isolated server disables security.
    // Ephemeral fixture-only values prove configuration transport, not authentication enforcement.
    private static String fixtureUser;
    private static String fixturePassword;
    private DbSwitchRuntime runtime;
    private String sourceIndex;
    private String targetIndex;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIELDS = List.of("_id", "ordinal", "large", "note");
    private static final String MAPPING = "{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{\"ordinal\":{\"type\":\"integer\"},\"large\":{\"type\":\"long\"},\"note\":{\"type\":\"keyword\"}}}}";

    @BeforeAll static void verifiedBundle() throws Exception {
        fixtureUser = UUID.randomUUID().toString(); fixturePassword = UUID.randomUUID().toString();
        String supplied = System.getProperty("datahub.test.elasticsearch.driver");
        assertNotNull(supplied, "Enabled Elasticsearch fixture requires its explicit JDBC bridge artifact");
        Path jar = Path.of(supplied).toAbsolutePath(); assertTrue(Files.isRegularFile(jar));
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(jar)) { byte[] bytes = new byte[8192]; int count; while ((count = input.read(bytes)) != -1) hash.update(bytes, 0, count); }
        var spec = new DbSwitchProperties.Driver(); spec.setPath(jar.toString());
        spec.setSha256(HexFormat.of().formatHex(hash.digest())); spec.setDriverClassName(ProductTypeEnum.ELASTICSEARCH.getDriveClassName());
        drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.ELASTICSEARCH, spec));
    }
    @AfterAll static void closeBundle() { if (drivers != null) drivers.close(); }
    @BeforeEach void setup() throws Exception {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
        String id = UUID.randomUUID().toString().replace("-", ""); sourceIndex = "es_source_" + id; targetIndex = "es_target_" + id;
        request("PUT", "/" + sourceIndex, MAPPING); request("PUT", "/" + targetIndex, MAPPING);
    }
    @AfterEach void cleanup() throws Exception {
        if (sourceIndex != null) request("DELETE", "/" + sourceIndex, null);
        if (targetIndex != null) request("DELETE", "/" + targetIndex, null);
        if (runtime != null) runtime.close();
        assertEquals(0, drivers.runtimes().connections().snapshot().active());
    }

    @Test void fullMigrationReadsBeyondOld500LimitAndPreservesIdentityBigintProjectionAndOrder() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT);
        writer.prepareWrite("es", sourceIndex, FIELDS);
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 503; i++) rows.add(new Object[]{"id-" + i, i, 9_007_199_254_740_993L + i, "中文-" + i});
        assertEquals(503, writer.write(FIELDS, rows));
        var reader = runtime.reader(source(), ProductTypeEnum.ELASTICSEARCH); reader.setFetchSize(73);
        try (var result = reader.queryTableData("es", sourceIndex, List.of("note", "large", "ordinal"), List.of("ordinal"))) {
            var rs = result.getResultset(); int count = 0;
            while (rs.next()) {
                assertEquals("中文-" + count, rs.getString(1)); assertEquals(9_007_199_254_740_993L + count, rs.getObject("large"));
                assertEquals(count, rs.getObject(3)); count++;
            }
            assertEquals(503, count);
        }
        MigrationRequest request = migration();
        var outcome = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(outcome.getIfAllSuccess().get(), "Actual existing-index FULL migration must succeed");
        JsonNode result = request("POST", "/" + targetIndex + "/_search", "{\"size\":1,\"sort\":[{\"ordinal\":\"desc\"}]}" );
        assertEquals(503, result.path("hits").path("total").path("value").asInt());
        assertEquals("id-502", result.path("hits").path("hits").get(0).path("_id").asText());
        assertEquals(9_007_199_254_741_495L, result.path("hits").path("hits").get(0).path("_source").path("large").longValue());
    }

    @Test void partialBulkReportsCountsDoesNotOverwriteAndIsImmediatelySearchable() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT); writer.prepareWrite("es", targetIndex, FIELDS);
        assertEquals(1, writer.write(FIELDS, List.<Object[]>of(new Object[]{"same", 1, 4L, "original"})));
        var failure = assertThrows(PartialBatchWriteException.class, () -> writer.write(FIELDS, List.of(
                new Object[]{"same", 2, 5L, "must-not-replace"}, new Object[]{"second", 3, 6L, "accepted"})));
        assertEquals(1, failure.acknowledgedRows()); assertEquals(1, failure.rejectedRows());
        var result = request("POST", "/" + targetIndex + "/_search", "{\"sort\":[{\"ordinal\":\"asc\"}]}" );
        assertEquals(2, result.path("hits").path("total").path("value").asInt());
        assertEquals("original", result.path("hits").path("hits").get(0).path("_source").path("note").asText());
    }

    @Test void badLaterValueIsRejectedBeforeAnyBulkSubmissionAndPrepareKeepsDocuments() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT); writer.prepareWrite("es", targetIndex, FIELDS);
        writer.write(FIELDS, List.<Object[]>of(new Object[]{"existing", 0, 0L, "keep"}));
        writer.prepareWrite("es", targetIndex, FIELDS);
        assertThrows(IllegalArgumentException.class, () -> writer.write(FIELDS, List.of(
                new Object[]{"first", 1, 7L, "not-sent"}, new Object[]{"bad", 2, new BigDecimal("1.5"), "reject"})));
        assertEquals(1, request("GET", "/" + targetIndex + "/_count", null).path("count").asInt());
    }

    @Test void explicitTruncateKeepsMappingAndDropActuallyRemovesIndex() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT); writer.prepareWrite("es", targetIndex, FIELDS);
        writer.write(FIELDS, List.<Object[]>of(new Object[]{"existing", 0, 0L, "remove"}));
        var reader = runtime.reader(source(), ProductTypeEnum.ELASTICSEARCH);
        reader.truncateTableData("es", targetIndex);
        assertEquals(0, request("GET", "/" + targetIndex + "/_count", null).path("count").asInt());
        assertEquals("long", request("GET", "/" + targetIndex + "/_mapping", null).path(targetIndex).path("mappings").path("properties").path("large").path("type").asText());
        reader.dropTable("es", targetIndex); request("PUT", "/" + targetIndex, MAPPING);
        assertFalse(reader.getPkExist("es", targetIndex, Map.of("_id", "existing")));
    }

    @Test void sameProductNativeCreateOnlyThenFullReplacePreservesMappingsAndSettings() throws Exception {
        var sourceWriter = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT);
        sourceWriter.prepareWrite("es", sourceIndex, FIELDS);
        sourceWriter.write(FIELDS, List.<Object[]>of(new Object[]{"source-id", 1, 9007199254740993L, "native"}));
        request("PUT", "/" + sourceIndex + "/_settings", "{\"index\":{\"number_of_replicas\":0}}" );
        request("DELETE", "/" + targetIndex, null);
        var request = migration(); request.getTarget().setOnlyCreate(true);
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals(0, request("GET", "/" + targetIndex + "/_count", null).path("count").asInt());
        assertEquals("0", request("GET", "/" + targetIndex + "/_settings?flat_settings=true", null)
                .path(targetIndex).path("settings").path("index.number_of_replicas").asText());
        var writer = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT); writer.prepareWrite("es", targetIndex, FIELDS);
        writer.write(FIELDS, List.<Object[]>of(new Object[]{"old-id", 99, 99L, "replace-only-explicitly"}));
        String oldUuid = request("GET", "/" + targetIndex + "/_settings?flat_settings=true", null)
                .path(targetIndex).path("settings").path("index.uuid").asText();
        request.getTarget().setOnlyCreate(false); request.getTarget().setTargetDrop(true);
        assertTrue(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        String newUuid = request("GET", "/" + targetIndex + "/_settings?flat_settings=true", null)
                .path(targetIndex).path("settings").path("index.uuid").asText();
        assertNotEquals(oldUuid, newUuid);
        var actual = request("POST", "/" + targetIndex + "/_search", "{\"query\":{\"match_all\":{}}}" );
        assertEquals(1, actual.path("hits").path("total").path("value").asInt());
        assertEquals("source-id", actual.path("hits").path("hits").get(0).path("_id").asText());
        assertEquals(9007199254740993L, actual.path("hits").path("hits").get(0).path("_source").path("large").longValue());
    }

    @Test void externalLifecycleSettingRejectsBeforeDeletingExistingTarget() throws Exception {
        request("PUT", "/" + sourceIndex + "/_settings", "{\"index\":{\"lifecycle.name\":\"undeployed_policy\"}}" );
        var writer = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT); writer.prepareWrite("es", targetIndex, FIELDS);
        writer.write(FIELDS, List.<Object[]>of(new Object[]{"keep", 1, 1L, "unchanged"}));
        var request = migration(); request.getTarget().setTargetDrop(true);
        assertFalse(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        assertEquals("unchanged", request("GET", "/" + targetIndex + "/_doc/keep", null).path("_source").path("note").asText());
    }

    @Test void stalePhysicalTargetCannotExecuteAnEarlierReplacePlan() throws Exception {
        var reader = runtime.reader(source(), ProductTypeEnum.ELASTICSEARCH);
        var definition = reader.describeNativeTable("es", sourceIndex);
        var identityFields = new LinkedHashMap<String, String>(); FIELDS.forEach(name -> identityFields.put(name, name));
        var plan = reader.prepareNativeTarget(definition, "es", targetIndex, identityFields, true);
        request("DELETE", "/" + targetIndex, null); request("PUT", "/" + targetIndex, MAPPING);
        request("PUT", "/" + targetIndex + "/_doc/new-owner?refresh=true", "{\"ordinal\":1,\"large\":1,\"note\":\"new owner\"}" );
        assertThrows(IllegalStateException.class, plan::execute);
        assertEquals("new owner", request("GET", "/" + targetIndex + "/_doc/new-owner", null).path("_source").path("note").asText());
        assertThrows(IllegalStateException.class, plan::execute);
    }

    @Test void nativeSynchronizerUpdatesOnlyExplicitFieldsAndDoesNotUpsertMissingDocuments() throws Exception {
        var writer = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT);
        writer.prepareWrite("es", targetIndex, FIELDS);
        writer.write(FIELDS, List.<Object[]>of(new Object[]{"existing", 7, 1L, "retain omitted fields"}));
        var sync = runtime.synchronizer(source(), ProductTypeEnum.ELASTICSEARCH);
        sync.prepare("es", targetIndex, List.of("_id", "large"), List.of("_id"));
        var partial = assertThrows(PartialBatchWriteException.class, () -> sync.executeUpdate(List.of(
                new Object[]{"existing", 9007199254740993L}, new Object[]{"missing", 2L})));
        assertEquals(1, partial.acknowledgedRows()); assertEquals(1, partial.rejectedRows());
        assertEquals(0, partial.unknownRows()); assertTrue(partial.terminationConfirmed());
        var actual = request("GET", "/" + targetIndex + "/_doc/existing", null).path("_source");
        assertEquals(9007199254740993L, actual.path("large").longValue());
        assertEquals(7, actual.path("ordinal").intValue()); assertEquals("retain omitted fields", actual.path("note").textValue());
        assertEquals(1, request("GET", "/" + targetIndex + "/_count", null).path("count").asInt());
        sync.prepare("es", targetIndex, List.of("_id"), List.of("_id"));
        assertEquals(1, sync.executeDelete(List.<Object[]>of(new Object[]{"existing"})));
        var missing = assertThrows(PartialBatchWriteException.class,
                () -> sync.executeDelete(List.<Object[]>of(new Object[]{"existing"})));
        assertEquals(0, missing.acknowledgedRows()); assertEquals(1, missing.rejectedRows());
        assertTrue(missing.terminationConfirmed());
        assertEquals(0, request("GET", "/" + targetIndex + "/_count", null).path("count").asInt());
    }

    @Test void migrationDiffUsesSharedKeyOrderingForNativeInsertUpdateDeleteAndRetainsIdenticalVersion() throws Exception {
        var sourceWriter = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT);
        var targetWriter = runtime.writer(source(), ProductTypeEnum.ELASTICSEARCH, WriteMode.INSERT);
        sourceWriter.prepareWrite("es", sourceIndex, FIELDS); targetWriter.prepareWrite("es", targetIndex, FIELDS);
        var sourceRows = List.of(new Object[]{"中文", 4, 9007199254740994L, "changed"},
                new Object[]{"a", 2, 9007199254740992L, "changed"},
                new Object[]{"new", 5, 9007199254740995L, "inserted"},
                new Object[]{"A", 1, 9007199254740991L, "identical"},
                new Object[]{"ä", 3, 9007199254740993L, "identical"});
        sourceWriter.write(FIELDS, sourceRows);
        targetWriter.write(FIELDS, List.of(new Object[]{"gone", 8, 8L, "delete"},
                new Object[]{"a", 2, 2L, "old"}, new Object[]{"ä", 3, 9007199254740993L, "identical"},
                new Object[]{"A", 1, 9007199254740991L, "identical"}, new Object[]{"中文", 4, 4L, "old"}));
        long unchangedVersion = request("GET", "/" + targetIndex + "/_doc/A", null).path("_version").longValue();
        var migration = migration(); migration.getTarget().setChangeDataSync(true);
        migration.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        assertTrue(new MigrationService(migration, ignored -> { }, null, runtime).run().getIfAllSuccess().get(),
                "Native DIFF must consume the shared bounded key comparison rather than ES _id sort");
        var result = request("POST", "/" + targetIndex + "/_search", "{\"size\":20,\"sort\":[{\"ordinal\":\"asc\"}]}" );
        assertEquals(5, result.path("hits").path("total").path("value").asInt());
        var hits = result.path("hits").path("hits");
        List<String> expectedIds = List.of("A", "a", "ä", "中文", "new");
        for (int i = 0; i < expectedIds.size(); i++) {
            assertEquals(expectedIds.get(i), hits.get(i).path("_id").textValue());
            assertEquals(9007199254740991L + i, hits.get(i).path("_source").path("large").longValue());
        }
        assertEquals("changed", hits.get(1).path("_source").path("note").textValue());
        assertEquals("changed", hits.get(3).path("_source").path("note").textValue());
        assertEquals(unchangedVersion, request("GET", "/" + targetIndex + "/_doc/A", null).path("_version").longValue());
    }

    private MigrationRequest migration() {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.ELASTICSEARCH); source.setUrl(url()); source.setUsername(fixtureUser); source.setPassword(fixturePassword);
        source.setDriverClassName(ProductTypeEnum.ELASTICSEARCH.getDriveClassName()); source.setSourceSchema("es");
        source.setSourceIncludes(sourceIndex); source.setPoolSize(1); source.setFetchSize(73);
        source.setRegexTableMapper(List.of(new PatternMapper("^" + sourceIndex + "$", targetIndex))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.ELASTICSEARCH); target.setUrl(url());
        target.setUsername(fixtureUser); target.setPassword(fixturePassword); target.setDriverClassName(ProductTypeEnum.ELASTICSEARCH.getDriveClassName());
        target.setExclusiveTargetDdlOwnership(true); target.setTargetSchema("es"); target.setTargetTable(targetIndex); target.setPoolSize(1); return request;
    }
    private AbstractDataSource source() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return runtime.openConnection(ProductTypeEnum.ELASTICSEARCH, null, url(), fixtureUser, fixturePassword); }
            @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Fixture credentials are fixed"); }
        };
    }
    private static String url() { URI endpoint = URI.create(System.getProperty("datahub.test.elasticsearch.http")); return "jdbc:jest://" + endpoint.getHost() + ":" + endpoint.getPort(); }
    private static JsonNode request(String method, String path, String body) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.elasticsearch.http") + path))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "Fixture operation failed: HTTP " + response.statusCode());
            return JSON.readTree(response.body());
        }
    }
}
