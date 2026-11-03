package cn.iocoder.yudao.framework.etl.core.schema;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class EtlSchemaPostgreSqlTest {
    private Connection control;
    private String schema;
    private EtlConnectionInfo info;
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "etl_schema_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".source_rows(amount numeric(38,18) NOT NULL, unconstrained numeric,"
                + " rounded numeric(4,-2), tiny numeric(2,4), id integer NOT NULL, external_id bigint,"
                + " event_at timestamp with time zone, local_at timestamp without time zone)");
        info = new EtlConnectionInfo(); info.setDatabaseId(1L); info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setJdbcUrl(url); info.setUsername(user); info.setPassword(""); info.setSchema(schema);
        runtime = DbSwitchRuntime.standalone();
    }

    @AfterEach void cleanup() throws Exception {
        if (runtime != null) runtime.close();
        if (control != null) { try { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); } finally { control.close(); } }
    }

    @Test void nativeDecimalAndNullableFactsPropagateWithoutInventingIntegerOrTimezonePrecision() {
        var read = read(false);
        var rename = node("rename", "column-map", 2, Map.of("regexColumnMapper", List.of(Map.of("fromPattern", "^amount$", "toValue", "measure"))));
        var sort = node("sort", "sort-records", 2, Map.of("sorts", List.of(Map.of("field", "id", "direction", "asc"))));
        var result = new EtlSchemaService(runtime).resolve(EtlDag.build(List.of(read, rename, sort),
                List.of(edge("read", "rename"), edge("rename", "sort"))), ignored -> info, null);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var fields = result.getNodes().get("sort").getOutputFields();
        assertEquals(Boolean.FALSE, field(fields, "measure").getNullable());
        assertEquals(38, field(fields, "measure").getPrecision()); assertEquals(18, field(fields, "measure").getScale());
        assertEquals(Boolean.TRUE, field(fields, "unconstrained").getNullable());
        assertNull(field(fields, "unconstrained").getPrecision()); assertNull(field(fields, "unconstrained").getScale());
        assertEquals(4, field(fields, "rounded").getPrecision()); assertEquals(-2, field(fields, "rounded").getScale());
        assertEquals(2, field(fields, "tiny").getPrecision()); assertEquals(4, field(fields, "tiny").getScale());
        assertEquals(Boolean.FALSE, field(fields, "id").getNullable());
        assertNull(field(fields, "id").getPrecision()); assertNull(field(fields, "external_id").getPrecision());
        fields.forEach(value -> assertNull(value.getTimezone(), "Native type is not proof of an IANA timezone"));
        assertEquals(38, field(result.getNodes().get("read").getOutputFields(), "amount").getPrecision());
    }

    @Test void sqlExpressionsHaveUnknownConstraintsInsteadOfBorrowingTheReferencedTableContract() {
        var result = new EtlSchemaService(runtime).resolve(EtlDag.build(List.of(read(true)), List.of()), ignored -> info, null);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var fields = result.getNodes().get("read").getOutputFields(); assertEquals(2, fields.size());
        fields.forEach(field -> { assertNull(field.getNullable()); assertNull(field.getPrecision());
            assertNull(field.getScale()); assertNull(field.getTimezone()); });
    }

    private EtlNodeDef read(boolean sql) {
        return node("read", "read-db", 1, sql ? Map.of("databaseId", 1, "ifSql", 1,
                "sql", "select amount + 0.0000000000000000001 as changed, event_at from " + schema + ".source_rows")
                : Map.of("databaseId", 1, "tableName", "source_rows"));
    }
    private static EtlFieldMeta field(List<EtlFieldMeta> fields, String name) {
        return fields.stream().filter(field -> name.equals(field.getName())).findFirst().orElseThrow();
    }
    private static EtlNodeDef node(String id, String code, int type, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type);
        node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlEdgeDef edge(String source, String target) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); edge.setEdgeType("DATA"); return edge;
    }
    private void sql(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
}
