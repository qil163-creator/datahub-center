package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class NativeSchemaNegativeScalePostgreSqlTest {
    private final String schema = "signed_scale_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach void createSchema() throws Exception { sql("CREATE SCHEMA " + schema); }
    @AfterEach void dropSchema() throws Exception { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }

    @Test void signedScaleBoundsKeepPositiveUnboundedAndIntegerFactsDistinct() throws Exception {
        sql("CREATE TABLE " + schema + ".source (rounded numeric(4,-2), low numeric(1000,-1000),"
                + " high numeric(1000,1000), fraction numeric(2,4), unbounded numeric, id integer)");
        var table = read("source");
        assertEquals(-2, facts(table, "rounded").numericScale().value());
        assertEquals(4, facts(table, "rounded").numericPrecision().value());
        assertEquals(-1000, facts(table, "low").numericScale().value());
        assertEquals(1000, facts(table, "high").numericScale().value());
        assertEquals(4, facts(table, "fraction").numericScale().value());
        assertNull(facts(table, "unbounded").numericScale().value());
        assertEquals(0, facts(table, "id").numericScale().value());
    }

    @Test void domainAndViewNumericFactsUseTheSameSignedCatalogSemantics() throws Exception {
        sql("CREATE DOMAIN " + schema + ".rounded_amount AS numeric(4,-2) NOT NULL");
        sql("CREATE TABLE " + schema + ".source (amount " + schema + ".rounded_amount)");
        sql("CREATE VIEW " + schema + ".viewed AS SELECT amount FROM " + schema + ".source");
        assertEquals(-2, facts(read("source"), "amount").numericScale().value());
        assertEquals(-2, facts(read("viewed"), "amount").numericScale().value());
        assertEquals(Boolean.FALSE, facts(read("source"), "amount").nullable().value());
        assertEquals(Boolean.FALSE, facts(read("viewed"), "amount").nullable().value());
    }

    private static ColumnMetadataEvidence facts(TableSchemaMetadata table, String name) {
        return table.columns().stream().filter(column -> name.equals(column.getFieldName()))
                .findFirst().orElseThrow().getMetadataEvidence();
    }
    private TableSchemaMetadata read(String table) {
        try (var runtime = DbSwitchRuntime.standalone()) {
            return runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL).queryTableSchema(url(), user(), "", schema, table, 5000);
        }
    }
    private void sql(String statement) throws Exception {
        try (var connection = DriverManager.getConnection(url(), user(), ""); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute(statement);
        }
    }
    private static String url() { return System.getProperty("datahub.test.postgres.url"); }
    private static String user() { return System.getProperty("datahub.test.postgres.user", "dataforge_test"); }
}
