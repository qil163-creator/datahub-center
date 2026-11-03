package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class SchemaChangeFreshnessPostgreSqlTest {
    private final String schema = "ddl_fresh_" + UUID.randomUUID().toString().replace("-", "");
    private final DatabasePostgresImpl database = new DatabasePostgresImpl();

    @BeforeEach void setup() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) { sql.execute("CREATE SCHEMA " + schema); }
    }
    @AfterEach void cleanup() throws Exception {
        assertTrue(schema.matches("ddl_fresh_[a-f0-9]{32}"));
        try (var connection = open(); var sql = connection.createStatement()) { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @Test void literalDefaultWhitespaceDriftStopsBeforeAnyDdl() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + schema + ".sample(note text DEFAULT 'a  b')");
            var frozen = observe(connection).precondition();
            sql.execute("ALTER TABLE " + schema + ".sample ALTER COLUMN note SET DEFAULT 'a b'");
            connection.setAutoCommit(false);
            AtomicInteger writes = new AtomicInteger();
            assertThrows(SQLException.class, () -> PostgresSchemaChanges.execute(connection, database,
                    plan("ALTER TABLE " + schema + ".sample ADD COLUMN extra int"), frozen, MetadataReadOptions.fresh(), ignored -> writes.incrementAndGet()));
            assertEquals(0, writes.get()); connection.rollback();
            assertEquals(1, observe(connection).metadata().columns().size());
            assertFalse(connection.getAutoCommit()); assertFalse(connection.isClosed());
        }
    }

    @Test void identicalShapeDropRecreateStillInvalidatesRelationIdentity() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + schema + ".sample(id int)");
            var frozen = observe(connection).precondition();
            sql.execute("DROP TABLE " + schema + ".sample"); sql.execute("CREATE TABLE " + schema + ".sample(id int)");
            assertEquals(frozen.schemaFingerprint(), observe(connection).precondition().schemaFingerprint());
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> PostgresSchemaChanges.execute(connection, database,
                    plan("ALTER TABLE " + schema + ".sample ADD COLUMN extra int"), frozen, MetadataReadOptions.fresh(), ignored -> fail("stale relation executed")));
            connection.rollback();
        }
    }

    @Test void lockCoversFreshnessCheckEveryStatementAndPostObservationWithoutCommittingCaller() throws Exception {
        try (var connection = open(); var sql = connection.createStatement(); var competitor = open()) {
            sql.execute("CREATE TABLE " + schema + ".sample(id int PRIMARY KEY)");
            var before = observe(connection);
            var addition = new ColumnDescription(); addition.setFieldName("new label"); addition.setNullable(true);
            SqlTypeSemantics.applyDeclaredType(addition, "varchar", 30, null, null);
            var changes = new SchemaDiff().planAddColumns(database, schema, "sample", List.of(addition), before.metadata());
            assertTrue(changes.ready()); assertEquals(DdlPlan.Kind.ADD_COLUMN, changes.steps().getFirst().kind());
            assertNull(changes.desiredFingerprint(), "post-DDL native evidence must not be fabricated");
            connection.setAutoCommit(false);
            AtomicInteger writes = new AtomicInteger();
            var after = PostgresSchemaChanges.execute(connection, database, plan(changes.steps().getFirst().sql()),
                    before.precondition(), MetadataReadOptions.fresh(), ordinal -> {
                        writes.incrementAndGet();
                        assertThrows(SQLException.class, () -> {
                            try (var rival = competitor.createStatement()) {
                                rival.execute("SET lock_timeout='150ms'");
                                rival.execute("ALTER TABLE " + schema + ".sample ADD COLUMN competitor int");
                            }
                        });
                    });
            assertEquals(1, writes.get()); assertEquals(2, after.metadata().columns().size());
            assertFalse(connection.getAutoCommit()); assertFalse(connection.isClosed());
            connection.rollback();
            assertEquals(1, observe(connection).metadata().columns().size());
        }
    }

    @Test void absentTargetBecomingPresentIsNotOverwritten() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            var frozen = observe(connection).precondition(); assertFalse(frozen.tableExists());
            sql.execute("CREATE TABLE " + schema + ".sample(id int)"); sql.execute("INSERT INTO " + schema + ".sample VALUES(7)");
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> PostgresSchemaChanges.execute(connection, database,
                    plan("CREATE TABLE " + schema + ".sample(id int)"), frozen, MetadataReadOptions.fresh(), ignored -> fail("unexpected write")));
            connection.rollback();
            try (var rows = sql.executeQuery("SELECT id FROM " + schema + ".sample")) { assertTrue(rows.next()); assertEquals(7, rows.getInt(1)); }
        }
    }

    @Test void unknownDependenciesAndNonNullableAddAreBlockedBeforeExecutablePrefix() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + schema + ".sample(id int PRIMARY KEY)");
            var actual = observe(connection).metadata();
            var addition = new ColumnDescription(); addition.setFieldName("new_id"); addition.setNullable(false);
            SqlTypeSemantics.applyDeclaredType(addition, "bigint", null, null, null);
            var plan = new SchemaDiff().planAddColumns(database, schema, "sample", List.of(addition), actual);
            assertFalse(plan.ready()); assertTrue(plan.steps().isEmpty());
            var unknown = new ColumnDescription(); unknown.setFieldName("unknown"); unknown.setNullable(true);
            unknown.setValType(cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.TYPE_NONE);
            assertFalse(new SchemaDiff().planAddColumns(database, schema, "sample", List.of(unknown), actual).ready());
            sql.execute("CREATE INDEX additional ON " + schema + ".sample(id)");
            addition.setNullable(true);
            assertFalse(new SchemaDiff().planAddColumns(database, schema, "sample", List.of(addition), observe(connection).metadata()).ready());
        }
    }

    private SchemaObservation observe(Connection connection) throws SQLException {
        return PostgresSchemaChanges.observe(connection, database, schema, "sample", MetadataReadOptions.fresh());
    }
    private SqlScriptPlan plan(String sql) { return SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, sql); }
    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
    }
}
