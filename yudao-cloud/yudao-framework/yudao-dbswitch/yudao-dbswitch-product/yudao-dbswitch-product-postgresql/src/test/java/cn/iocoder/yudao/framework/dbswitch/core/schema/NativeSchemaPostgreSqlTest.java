package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class NativeSchemaPostgreSqlTest {
    private final String schema = "schema_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach void createSchema() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) { sql.execute("CREATE SCHEMA " + schema); }
    }
    @AfterEach void dropSchema() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) { sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }

    @Test void nativeColumnsRoundTripDefaultsIdentityDecimalTimezoneArraysAndKeyOrder() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + schema + ".source (id bigint GENERATED ALWAYS AS IDENTITY (START WITH 5 INCREMENT BY 3), "
                    + "code int NOT NULL, \"Display Name\" text DEFAULT 'a  (::)b'::text, amount numeric(200,40), "
                    + "created timestamp(3) with time zone DEFAULT now(), document jsonb, numbers integer[], PRIMARY KEY(code,id))");
            TableSchemaMetadata source = read("source");
            assertEquals(List.of("code", "id"), source.primaryKeys());
            assertEquals(MetadataValue.State.ABSENT, source.additionalObjects().state());
            assertTrue(source.columns().getFirst().getMetadataEvidence().identity().value().contains("START WITH 5 INCREMENT BY 3"));
            assertTrue(source.columns().get(2).getMetadataEvidence().defaultExpression().value().contains("a  (::)b"));
            assertEquals(200, source.columns().get(3).getMetadataEvidence().numericPrecision().value());
            assertEquals(40, source.columns().get(3).getMetadataEvidence().numericScale().value());
            sql.execute(GenerateSqlUtils.getDDLCreateTableSQL(new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), source, schema, "copy"));
            TableSchemaMetadata copy = read("copy");
            assertEquals(SchemaFingerprint.of(source), SchemaFingerprint.of(copy));
            assertTrue(new SchemaDiff().plan(new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), schema, "copy", source, copy).ready());
        }
    }

    @Test void wideningAndAddPlansCanBeAppliedThenReobserved() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + schema + ".actual (value varchar(10))");
            sql.execute("CREATE TABLE " + schema + ".desired (value varchar(20), extra text DEFAULT 'left;right'::text)");
            TableSchemaMetadata actual = read("actual");
            TableSchemaMetadata desired = read("desired");
            assertEquals(10L, actual.columns().getFirst().getMetadataEvidence().characterLength().value());
            assertEquals(20L, desired.columns().getFirst().getMetadataEvidence().characterLength().value());
            var unboundedLength = desired.columns().get(1).getMetadataEvidence().characterLength();
            assertEquals(MetadataValue.State.ABSENT, unboundedLength.state());
            assertNull(unboundedLength.value(), "SQL NULL must remain absent, not become a zero length");
            DdlPlan plan = new SchemaDiff().plan(new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), schema, "actual", desired, actual);
            assertTrue(plan.ready(), () -> plan.diagnostics().toString());
            assertEquals(2, plan.steps().size());
            connection.setAutoCommit(false);
            for (DdlPlan.Step step : plan.steps()) sql.execute(step.sql());
            connection.commit();
            assertEquals(SchemaFingerprint.of(read("desired")), SchemaFingerprint.of(read("actual")));
        }
    }

    @Test void unmodeledConstraintsAndGeneratedColumnsDoNotDisappearFromCreate() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + schema + ".guarded (id int CHECK (id>0), doubled int GENERATED ALWAYS AS (id*2) STORED)");
            TableSchemaMetadata metadata = read("guarded");
            assertEquals(MetadataValue.State.VALUE, metadata.additionalObjects().state());
            assertEquals(MetadataValue.State.VALUE, metadata.columns().get(1).getMetadataEvidence().generatedExpression().state());
            assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                    new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), metadata, schema, "copy"));
        }
    }

    private TableSchemaMetadata read(String table) {
        try (DbSwitchRuntime runtime = DbSwitchRuntime.standalone()) {
            return runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL).queryTableSchema(
                    System.getProperty("datahub.test.postgres.url"), username(), "", schema, table, 5000);
        }
    }

    @Test void viewsPartitionParentsAndChildrenCannotBePlannedAsOrdinaryTables() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + schema + ".partitioned (id int) PARTITION BY RANGE (id)");
            sql.execute("CREATE TABLE " + schema + ".partition_child PARTITION OF " + schema + ".partitioned FOR VALUES FROM (0) TO (100)");
            sql.execute("CREATE VIEW " + schema + ".viewed AS SELECT id FROM " + schema + ".partitioned");
            sql.execute("CREATE TABLE " + schema + ".inheritance_parent (id int)");
            sql.execute("CREATE TABLE " + schema + ".inherited (extra text) INHERITS (" + schema + ".inheritance_parent)");
            for (String name : List.of("partitioned", "partition_child", "viewed", "inherited")) {
                TableSchemaMetadata metadata = read(name);
                assertEquals(MetadataValue.State.VALUE, metadata.additionalObjects().state(), name);
                assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                        new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), metadata, schema, "copy"));
                assertFalse(new SchemaDiff().plan(new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), schema, name, metadata, metadata).ready());
            }
        }
    }
    private Connection open() throws Exception {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), username(), "");
    }
    private String username() { return System.getProperty("datahub.test.postgres.user", "dataforge_test"); }
}
