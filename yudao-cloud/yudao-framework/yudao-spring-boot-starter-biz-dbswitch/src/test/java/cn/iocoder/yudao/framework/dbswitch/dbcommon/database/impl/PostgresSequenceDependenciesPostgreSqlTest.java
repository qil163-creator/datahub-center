package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class PostgresSequenceDependenciesPostgreSqlTest {
    private final String schema="sequence_graph_"+UUID.randomUUID().toString().replace("-","");
    @BeforeEach void setup() throws Exception {
        sql("CREATE SCHEMA "+schema,
                "CREATE SEQUENCE "+schema+".shared AS bigint START WITH 5 INCREMENT BY 3 CACHE 2",
                "CREATE SEQUENCE "+schema+".second AS bigint START WITH 11 INCREMENT BY 2",
                "CREATE TABLE "+schema+".a(id bigint DEFAULT nextval('"+schema+".shared'::regclass))",
                "ALTER SEQUENCE "+schema+".shared OWNED BY "+schema+".a.id",
                "CREATE TABLE "+schema+".b(id bigint DEFAULT nextval('"+schema+".shared'::regclass),other bigint DEFAULT nextval('"+schema+".second'::regclass))",
                "CREATE TABLE "+schema+".c(id bigint DEFAULT (nextval('"+schema+".second'::regclass)+10))",
                "CREATE TABLE "+schema+".plain(id integer)");
    }
    @AfterEach void cleanup() throws Exception {sql("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}

    @Test void sourceSelectionDiscoversTransitiveClosureWithoutExpandingOrAdvancingIt() throws Exception {
        var graph=graph(null,"a");
        assertEquals(Set.of(relation("a")),graph.selectedRelations());
        assertEquals(Set.of(relation("b"),relation("c")),graph.missingRelations());
        assertEquals(2,graph.sequences().size());
        assertThrows(IllegalArgumentException.class,graph::requireClosedSelection);
        var shared=sequence(graph,"shared");
        assertEquals(relation("a"),shared.owner().value().relation());assertTrue(shared.shared());
        assertEquals(2,shared.references().size());assertEquals(5,shared.definition().start());assertEquals(3,shared.definition().increment());
        assertEquals(5,shared.definition().position().value().lastValue());assertFalse(shared.definition().position().value().called());
        try(var connection=open();var query=connection.createStatement();var rows=query.executeQuery("SELECT last_value,is_called FROM "+schema+".shared")) {
            assertTrue(rows.next());assertEquals(5,rows.getLong(1));assertFalse(rows.getBoolean(2));
        }
        assertThrows(UnsupportedOperationException.class,()->graph.sequences().clear());
    }

    @Test void closedSelectionPreservesUnownedSequenceAndUnplannedComplexExpressionEvidence() throws Exception {
        var graph=graph(null,"a","b","c");assertDoesNotThrow(graph::requireClosedSelection);
        var second=sequence(graph,"second");assertEquals(MetadataValue.State.ABSENT,second.owner().state());
        var expression=second.references().stream().filter(reference->reference.column().relation().equals(relation("c"))).findFirst().orElseThrow();
        assertEquals(SequenceDependencyGraph.ExpressionEvidence.CATALOG_EXPRESSION_UNPLANNED,expression.expressionEvidence());
        assertTrue(expression.expression().value().contains("nextval("));assertTrue(expression.expression().value().contains("+ 10"));
        assertFalse(expression.toString().contains("nextval"),"Expressions must not leak through diagnostic toString");
        assertTrue(second.identity().sequenceOid()>0);assertTrue(second.identity().databaseOid()>0);
        assertNotNull(graph.catalogSnapshot().fingerprint());
    }

    @Test void identityOwnershipAndOtherDefaultUseBelongToOneSequenceNode() throws Exception {
        sql("CREATE TABLE "+schema+".identity_owner(id bigint GENERATED ALWAYS AS IDENTITY (START WITH 20 INCREMENT BY 5))",
                "CREATE TABLE "+schema+".identity_peer(id bigint DEFAULT nextval('"+schema+".identity_owner_id_seq'::regclass))");
        var graph=graph(null,"identity_owner");assertEquals(1,graph.sequences().size());
        var sequence=graph.sequences().getFirst();assertEquals(Set.of(relation("identity_peer")),graph.missingRelations());
        assertEquals(relation("identity_owner"),sequence.owner().value().relation());
        assertEquals(Set.of(SequenceDependencyGraph.ReferenceKind.IDENTITY_ALWAYS,SequenceDependencyGraph.ReferenceKind.DEFAULT_EXPRESSION),
                sequence.references().stream().map(SequenceDependencyGraph.Reference::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(sequence.shared());
    }

    @Test void cycleDefinitionIsObservedWithoutInventingMonotonicProgress() throws Exception {
        sql("CREATE SEQUENCE "+schema+".cyclic AS bigint START WITH 3 INCREMENT BY -2 MINVALUE -5 MAXVALUE 5 CYCLE",
                "CREATE TABLE "+schema+".cycle_table(id bigint DEFAULT nextval('"+schema+".cyclic'::regclass))");
        var graph=graph(null,"cycle_table");assertDoesNotThrow(graph::requireClosedSelection);
        var definition=graph.sequences().getFirst().definition();assertTrue(definition.cycle());assertEquals(-2,definition.increment());
        assertEquals(3,definition.position().value().lastValue());assertFalse(definition.position().value().called());
    }

    @Test void missingSequenceSelectPrivilegeLeavesPositionUnknownAndCatalogSnapshotUsable() throws Exception {
        String role="sequence_reader_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=open();var statement=connection.createStatement()) {
            statement.execute("CREATE ROLE "+role);statement.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA "+schema+" TO "+role);
            try {
                var graph=graph(role,"a","b","c");assertDoesNotThrow(graph::requireClosedSelection);
                assertEquals(2,graph.sequences().size());
                assertTrue(graph.sequences().stream().allMatch(sequence->sequence.definition().position().state()==MetadataValue.State.UNKNOWN));
            } finally {statement.execute("DROP OWNED BY "+role);statement.execute("DROP ROLE "+role);}
        }
    }

    @Test void emptyDependencySetIsProvedButMissingPhysicalRelationIsNeverAnEmptySuccess() throws Exception {
        var graph=graph(null,"plain");assertTrue(graph.sequences().isEmpty());assertDoesNotThrow(graph::requireClosedSelection);
        assertThrows(IllegalStateException.class,()->graph(null,"missing"));
    }

    private SequenceDependencyGraph graph(String role,String... tables) throws Exception {
        try(var runtime=DbSwitchRuntime.standalone()) {
            var source=new AbstractDataSource(){
                @Override public Connection getConnection()throws SQLException{
                    var connection=open();
                    if(role!=null)try(var statement=connection.createStatement()){statement.execute("SET ROLE "+role);}
                    return connection;
                }
                @Override public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
            };
            return runtime.reader(source,ProductTypeEnum.POSTGRESQL).planMigrationDependencies(Arrays.stream(tables).map(this::relation).toList());
        }
    }
    private SequenceDependencyGraph.Sequence sequence(SequenceDependencyGraph graph,String name) {
        return graph.sequences().stream().filter(sequence->sequence.identity().name().equals(name)).findFirst().orElseThrow();
    }
    private TableReadPlan.Relation relation(String table){return new TableReadPlan.Relation(schema,table);}
    private static void sql(String... values)throws SQLException{try(var connection=open();var statement=connection.createStatement()){for(String sql:values)statement.execute(sql);}}
    private static Connection open()throws SQLException{return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),System.getProperty("datahub.test.postgres.user","dataforge_test"),System.getProperty("datahub.test.postgres.password",""));}
}
