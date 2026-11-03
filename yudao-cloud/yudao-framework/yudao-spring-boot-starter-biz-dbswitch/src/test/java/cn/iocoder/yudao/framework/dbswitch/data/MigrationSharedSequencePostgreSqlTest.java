package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationSharedSequencePostgreSqlTest {
    private final String source="shared_src_"+UUID.randomUUID().toString().replace("-","");
    private final String target="shared_dst_"+UUID.randomUUID().toString().replace("-","");
    @BeforeEach void setup()throws Exception{sql("CREATE SCHEMA "+source,"CREATE SCHEMA "+target);}
    @AfterEach void cleanup()throws Exception{sql("DROP SCHEMA IF EXISTS "+source+" CASCADE","DROP SCHEMA IF EXISTS "+target+" CASCADE");}

    @ParameterizedTest @CsvSource({"true,true","false,true","true,false","false,false"})
    void actualTwoTableMigrationPreservesOneSequenceAndDefersCallbacks(boolean insert,boolean owned)throws Exception {
        createSource(owned,false,false);var callbacks=new ArrayList<DbSwitchTableResult>();
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request(insert),table->{
                try {
                    assertEquals(2,number("SELECT (SELECT count(*) FROM "+target+".copy_a)+(SELECT count(*) FROM "+target+".copy_b)"));
                    assertEquals(101,number("SELECT last_value FROM "+target+"."+targetSequence()));
                }catch(Exception failure){throw new AssertionError(failure);}
                callbacks.add(table);
            },null,runtime).run();
            assertTrue(result.getIfAllSuccess().get());assertEquals(2,result.getTotalRowCount().get());
        }
        assertEquals(2,callbacks.size());assertTrue(callbacks.stream().allMatch(row->row.getIfSuccess().get()));
        assertEquals(1,number("SELECT count(DISTINCT dep.refobjid) FROM pg_catalog.pg_depend dep JOIN pg_catalog.pg_attrdef d ON d.oid=dep.objid JOIN pg_catalog.pg_class c ON c.oid=d.adrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace JOIN pg_catalog.pg_class s ON s.oid=dep.refobjid AND s.relkind='S' WHERE dep.classid='pg_attrdef'::regclass AND dep.refclassid='pg_class'::regclass AND n.nspname='"+target+"'"));
        assertEquals(5,number("SELECT last_value FROM "+source+".shared"));
        assertEquals(101,number("INSERT INTO "+target+".copy_a(note) VALUES('generated') RETURNING key_id"));
        assertEquals(104,number("INSERT INTO "+target+".copy_b(note) VALUES('generated') RETURNING key_id"));
        assertEquals(2,number("SELECT count(*) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+target+"' AND k.contype='p'"));
    }

    @ParameterizedTest @CsvSource({"APPEND","TRUNCATE","DROP"})
    void existingSharedTargetPositionNeverMovesBackAcrossReplacementModes(String mode)throws Exception {
        createSource(true,false,false);createTarget(false);
        sql("ALTER SEQUENCE "+target+".existing_shared RESTART WITH 1001");
        var request=request(true);request.getTarget().setTargetDrop(mode.equals("DROP"));request.getTarget().setTargetTruncateData(mode.equals("TRUNCATE"));
        try(var runtime=DbSwitchRuntime.standalone()){assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());}
        assertEquals(1001,number("INSERT INTO "+target+".copy_b(note) VALUES('generated') RETURNING key_id"));
    }

    @Test void descendingSequenceUsesAllReferencedTablesBeforeRestart()throws Exception {
        createSource(false,true,false);
        try(var runtime=DbSwitchRuntime.standalone()){assertTrue(new MigrationService(request(false),ignored->{},null,runtime).run().getIfAllSuccess().get());}
        assertEquals(-103,number("INSERT INTO "+target+".copy_a(note) VALUES('generated') RETURNING key_id"));
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void replacementRetainsValuesIssuedAfterPreflightInBothSequenceDirections(boolean descending,boolean structureOnly)throws Exception {
        createSource(true,descending,false);
        sql("CREATE SEQUENCE "+target+".existing_shared AS bigint START WITH "+(descending?"-7 INCREMENT BY -3 MINVALUE -1000 MAXVALUE -1":"5 INCREMENT BY 3 MINVALUE 1 MAXVALUE 10000"),
                "CREATE TABLE "+target+".copy_a(key_id bigint DEFAULT nextval('"+target+".existing_shared'::regclass) PRIMARY KEY,note text)",
                "CREATE TABLE "+target+".copy_b(key_id bigint DEFAULT nextval('"+target+".existing_shared'::regclass) PRIMARY KEY,note text)",
                "ALTER SEQUENCE "+target+".existing_shared OWNED BY "+target+".copy_a.key_id");
        try(var runtime=DbSwitchRuntime.standalone()) {
            var input=new org.springframework.jdbc.datasource.AbstractDataSource(){
                @Override public Connection getConnection()throws SQLException{return open();}
                @Override public Connection getConnection(String name,String secret)throws SQLException{return getConnection();}
            };
            var a=new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(source,"a");
            var b=new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(source,"b");
            var graph=runtime.reader(input,ProductTypeEnum.POSTGRESQL).planMigrationDependencies(List.of(a,b));
            try(var pool=cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils.createTargetDataSource(request(true).getTarget(),null,null,null,runtime.drivers().runtimes(),runtime.options(),120000L)) {
                var plans=List.of(a,b).stream().map(relation->new cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan(relation,
                        new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(target,"copy_"+relation.table()),Map.of("id","key_id","note","note"))).toList();
                var mode=structureOnly?cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode.STRUCTURE_ONLY
                        :cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode.DATA_AND_STRUCTURE;
                try(var scope=runtime.reader(pool,ProductTypeEnum.POSTGRESQL).prepareSequenceTransfer(graph,plans,true,false,false,mode)) {
                    // Deliberately issue a value after the constructor's observation and before the DDL locks.
                    long issued=descending?-700:1001;
                    sql("ALTER SEQUENCE "+target+".existing_shared RESTART WITH "+issued);
                    assertEquals(issued,number("SELECT nextval('"+target+".existing_shared')"));
                    var ddl=new LinkedHashMap<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation,List<String>>();
                    for(var plan:plans)ddl.put(plan.source(),List.of("CREATE TABLE "+target+"."+plan.target().table()+"(key_id bigint PRIMARY KEY,note text)"));
                    scope.prepare(ddl);
                    if(!structureOnly)sql("INSERT INTO "+target+".copy_a SELECT id,note FROM "+source+".a","INSERT INTO "+target+".copy_b SELECT id,note FROM "+source+".b");
                    scope.binding(a).tableCompleted();scope.binding(b).tableCompleted();scope.complete();
                    assertEquals(issued+(descending?-3:3),number("INSERT INTO "+target+".copy_b(note) VALUES('after replacement') RETURNING key_id"));
                }
            }
        }
    }

    @Test void targetReferenceOutsideSelectionRejectsBeforeAnyTruncate()throws Exception {
        createSource(true,false,false);createTarget(false);
        sql("CREATE TABLE "+target+".outside(key_id bigint DEFAULT nextval('"+target+".existing_shared'::regclass))",
                "INSERT INTO "+target+".copy_a VALUES(77,'retain')");
        var request=request(true);request.getTarget().setTargetTruncateData(true);
        try(var runtime=DbSwitchRuntime.standalone()){assertThrows(IllegalArgumentException.class,()->new MigrationService(request,ignored->{},null,runtime).run());}
        assertEquals(77,number("SELECT key_id FROM "+target+".copy_a"));
    }

    @Test void aFailedMemberNeverPublishesItsSuccessfulSiblingOrRewindsSharedState()throws Exception {
        createSource(true,false,false);createTarget(true);var callbacks=new ArrayList<DbSwitchTableResult>();
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request(true),callbacks::add,null,runtime).run();
            assertFalse(result.getIfAllSuccess().get());assertEquals(2,result.getTotalRowCount().get(),"Legacy totalRowCount counts source reads");
            assertEquals(1,result.getTableResultList().stream().mapToLong(row->row.getBatchProgress().acknowledgedRows()).sum());
            assertEquals(1,result.getTableResultList().stream().mapToLong(row->row.getBatchProgress().unknownRows()).sum());
        }
        assertEquals(2,callbacks.size());assertTrue(callbacks.stream().noneMatch(row->row.getIfSuccess().get()));
        assertEquals(1,number("SELECT count(*) FROM "+target+".copy_a"));assertEquals(0,number("SELECT count(*) FROM "+target+".copy_b"));
        assertEquals(5,number("SELECT last_value FROM "+target+".existing_shared"));
        assertTrue(callbacks.stream().anyMatch(row->row.getSyncCount().get()==1&&row.getTransferSummary()!=null));
    }

    @Test void sequenceExhaustionFailsGroupButKeepsConfirmedRowsAndReceipts()throws Exception {
        createSource(true,false,true);var callbacks=new ArrayList<DbSwitchTableResult>();
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request(true),callbacks::add,null,runtime).run();
            assertFalse(result.getIfAllSuccess().get());assertEquals(2,result.getTotalRowCount().get());
        }
        assertEquals(2,callbacks.size());assertTrue(callbacks.stream().noneMatch(row->row.getIfSuccess().get()));
        assertEquals(2,number("SELECT (SELECT count(*) FROM "+target+".copy_a)+(SELECT count(*) FROM "+target+".copy_b)"));
        assertTrue(callbacks.stream().allMatch(row->row.getTransferSummary()!=null));
    }

    @Test void lateStructureFailureRollsBackAllNewTablesAndTheSharedSequence()throws Exception {
        createSource(true,false,false);sql("CREATE VIEW "+target+".copy_b AS SELECT 1 AS key_id,'retain'::text AS note");
        try(var runtime=DbSwitchRuntime.standalone()){assertThrows(RuntimeException.class,()->new MigrationService(request(true),ignored->{},null,runtime).run());}
        assertEquals(0,number("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+target+"' AND c.relkind IN ('r','S')"));
        assertEquals(1,number("SELECT key_id FROM "+target+".copy_b"));
    }

    @Test void firstCallbackFailureStillDeliversEveryCommittedSiblingReceipt()throws Exception {
        createSource(true,false,false);var callbacks=new ArrayList<DbSwitchTableResult>();
        try(var runtime=DbSwitchRuntime.standalone()) {
            assertThrows(IllegalStateException.class,()->new MigrationService(request(true),row->{
                callbacks.add(row);if(callbacks.size()==1)throw new IllegalStateException("fixture callback delivery failed");
            },null,runtime).run());
        }
        assertEquals(2,callbacks.size());
        assertTrue(callbacks.stream().allMatch(row->row.getIfSuccess().get()&&row.getTransferSummary()!=null));
        assertEquals(2,callbacks.stream().mapToLong(row->row.getTransferSummary().acknowledgedRows()).sum());
        assertEquals(101,number("SELECT last_value FROM "+target+"."+targetSequence()));
    }

    @Test void multipleSourcesCannotReplaceTheSameTargetAfterAnEarlierSuccess()throws Exception {
        createSource(true,false,false);createTarget(false);sql("INSERT INTO "+target+".copy_a VALUES(77,'retain')");
        var request=request(true);request.getSource().add(request.getSource().getFirst().copy());
        request.getTarget().setTargetDrop(true);
        request.getTarget().setBeforeSqlScripts("INSERT INTO "+target+".copy_a VALUES(333,'must-not-run')");
        var callbacks=new ArrayList<DbSwitchTableResult>();
        try(var runtime=DbSwitchRuntime.standalone()) {
            assertThrows(IllegalArgumentException.class,()->new MigrationService(request,callbacks::add,null,runtime).run());
        }
        assertTrue(callbacks.isEmpty());assertEquals(1,number("SELECT count(*) FROM "+target+".copy_a"));
        assertEquals(77,number("SELECT key_id FROM "+target+".copy_a"));assertEquals(0,number("SELECT count(*) FROM "+target+".copy_b"));
    }

    @Test void multipleSourcesWithDistinctTargetMappingsRemainExecutable()throws Exception {
        createSource(true,false,false);var request=request(true);var second=request.getSource().getFirst().copy();
        second.setRegexTableMapper(List.of(new PatternMapper("^(a|b)$","other_$1")));request.getSource().add(second);
        var callbacks=new ArrayList<DbSwitchTableResult>();
        try(var runtime=spy(DbSwitchRuntime.standalone())) {
            var service=new MigrationService(request,callbacks::add,null,runtime);
            var result=service.run();assertTrue(result.getIfAllSuccess().get());
            assertEquals(4,result.getTotalRowCount().get());
            service.physicalExit().toCompletableFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
            verify(runtime,never()).close();
            assertTrue(runtime.drivers().runtimes().statuses().stream().allMatch(status -> status.leases()==0
                    &&status.state()==cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry.State.READY));
            assertEquals(0,runtime.drivers().runtimes().connections().snapshot().workload());
            var later=request(true);later.getSource().getFirst().setRegexTableMapper(List.of(new PatternMapper("^(a|b)$","later_$1")));
            var nextService=new MigrationService(later,ignored->{},null,runtime);var next=nextService.run();
            nextService.physicalExit().toCompletableFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(next.getIfAllSuccess().get());assertEquals(2,next.getTotalRowCount().get());
            verify(runtime,never()).close();
        }
        assertEquals(4,callbacks.size());assertEquals(100,number("SELECT key_id FROM "+target+".other_b"));
        assertEquals(100,number("SELECT key_id FROM "+target+".copy_b"));
        assertEquals(100,number("SELECT key_id FROM "+target+".later_b"));
    }

    @Test void sourceBeforeScriptCannotExpandTheReservedTaskTargetSet()throws Exception {
        createSource(true,false,false);var request=request(true);var second=request.getSource().getFirst().copy();
        second.setRegexTableMapper(List.of(new PatternMapper("^(a|b)$","other_$1")));request.getSource().add(second);
        request.getSource().getFirst().setSourceIncludes("");
        request.getSource().getFirst().setBeforeSqlScripts("CREATE TABLE "+source+".new_relation(id bigint)");
        try(var runtime=DbSwitchRuntime.standalone()) {
            assertThrows(IllegalStateException.class,()->new MigrationService(request,ignored->{},null,runtime).run());
        }
        assertEquals(0,number("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+target+"'"));
        assertEquals(1,number("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+source+"' AND c.relname='new_relation'"));
    }

    @ParameterizedTest @CsvSource({"true","false"})
    void structureOnlyPreservesSharedDefaultsWithoutCopyingRowsOrSourceCounter(boolean owned)throws Exception {
        createSource(owned,false,false);sql("ALTER SEQUENCE "+source+".shared RESTART WITH 800");
        var request=request(false);request.getTarget().setOnlyCreate(true);var callbacks=new ArrayList<DbSwitchTableResult>();
        try(var runtime=spy(DbSwitchRuntime.standalone())) {
            var result=new MigrationService(request,row->{
                try{assertEquals(2,number("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+target+"' AND c.relkind='r'"));}
                catch(Exception failure){throw new AssertionError(failure);}callbacks.add(row);
            },null,runtime).run();
            assertTrue(result.getIfAllSuccess().get());assertEquals(0,result.getTotalRowCount().get());
            verify(runtime,never()).writer(any(),any(),any());
        }
        assertEquals(2,callbacks.size());assertTrue(callbacks.stream().allMatch(row->row.getIfSuccess().get()
                &&row.getTransferSummary().operation()==cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary.Operation.DDL_ONLY
                &&row.getTransferSummary().readRows()==0&&row.getTransferSummary().acknowledgedRows()==0));
        assertEquals(0,number("SELECT (SELECT count(*) FROM "+target+".copy_a)+(SELECT count(*) FROM "+target+".copy_b)"));
        assertEquals(5,number("INSERT INTO "+target+".copy_a(note) VALUES('new') RETURNING key_id"));
        assertEquals(8,number("INSERT INTO "+target+".copy_b(note) VALUES('new') RETURNING key_id"));
        assertEquals(800,number("SELECT last_value FROM "+source+".shared"));
    }

    @ParameterizedTest @CsvSource({"false","true"})
    void structureOnlyNeverTruncatesAndReplacementPreservesPriorTargetPosition(boolean replace)throws Exception {
        createSource(true,false,false);createTarget(false);
        sql("INSERT INTO "+target+".copy_a VALUES(77,'retain')","ALTER SEQUENCE "+target+".existing_shared RESTART WITH 1001");
        var request=request(true);request.getTarget().setOnlyCreate(true);request.getTarget().setTargetDrop(replace);request.getTarget().setTargetTruncateData(true);
        try(var runtime=DbSwitchRuntime.standalone()){assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());}
        assertEquals(replace?0:1,number("SELECT count(*) FROM "+target+".copy_a"));
        assertEquals(1001,number("INSERT INTO "+target+".copy_b(note) VALUES('new') RETURNING key_id"));
    }

    @Test void structureOnlyLateDdlFailureCannotPublishOrRetainPartOfTheGroup()throws Exception {
        createSource(true,false,false);sql("CREATE VIEW "+target+".copy_b AS SELECT 9 AS key_id,'retain'::text AS note");
        var request=request(true);request.getTarget().setOnlyCreate(true);var callbacks=new ArrayList<DbSwitchTableResult>();
        try(var runtime=DbSwitchRuntime.standalone()){assertThrows(RuntimeException.class,()->new MigrationService(request,callbacks::add,null,runtime).run());}
        assertTrue(callbacks.isEmpty());assertEquals(0,number("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+target+"' AND c.relkind IN ('r','S')"));
        assertEquals(9,number("SELECT key_id FROM "+target+".copy_b"));
    }

    @Test void structureOnlyCanUseRealCatalogEvidenceWithoutSequenceSelectPrivilege()throws Exception {
        createSource(true,false,false);String role="shared_reader_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE ROLE "+role,"GRANT USAGE ON SCHEMA "+source+" TO "+role,"GRANT SELECT ON ALL TABLES IN SCHEMA "+source+" TO "+role);
        try(var runtime=DbSwitchRuntime.standalone()) {
            var limited=new org.springframework.jdbc.datasource.AbstractDataSource(){
                @Override public Connection getConnection()throws SQLException{var connection=open();try(var statement=connection.createStatement()){statement.execute("SET ROLE "+role);}return connection;}
                @Override public Connection getConnection(String name,String secret)throws SQLException{return getConnection();}
            };
            var a=new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(source,"a");var b=new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(source,"b");
            var graph=runtime.reader(limited,ProductTypeEnum.POSTGRESQL).planMigrationDependencies(List.of(a,b));
            assertTrue(graph.sequences().stream().allMatch(sequence->sequence.definition().position().state()==cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.UNKNOWN));
            var output=request(true).getTarget();
            try(var pool=cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils.createTargetDataSource(output,null,null,null,runtime.drivers().runtimes(),runtime.options(),120000L)) {
                var plans=List.of(a,b).stream().map(relation->new cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TablePlan(relation,
                        new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(target,"copy_"+relation.table()),Map.of("id","key_id","note","note"))).toList();
                try(var scope=runtime.reader(pool,ProductTypeEnum.POSTGRESQL).prepareSequenceTransfer(graph,plans,false,false,false,
                        cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.Mode.STRUCTURE_ONLY)) {
                    var ddl=new LinkedHashMap<cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation,List<String>>();
                    for(var plan:plans)ddl.put(plan.source(),List.of("CREATE TABLE "+target+"."+plan.target().table()+"(key_id bigint PRIMARY KEY,note text)"));
                    scope.prepare(ddl);
                    assertThrows(IllegalStateException.class,()->scope.binding(a).prepareWriter(target,"copy_a",List.of("key_id","note")));
                    scope.binding(a).tableCompleted();scope.binding(b).tableCompleted();scope.complete();
                }
            }
            assertEquals(5,number("INSERT INTO "+target+".copy_b(note) VALUES('new') RETURNING key_id"));
        } finally {sql("DROP OWNED BY "+role,"DROP ROLE "+role);}
    }

    private void createSource(boolean owned,boolean descending,boolean exhausted)throws Exception {
        sql("CREATE SEQUENCE "+source+".shared AS bigint START WITH "+(descending?"-7 INCREMENT BY -3 MINVALUE -1000 MAXVALUE -1":"5 INCREMENT BY 3 MINVALUE 1 MAXVALUE "+(exhausted?"100":"10000")),
                "CREATE TABLE "+source+".a(id bigint DEFAULT nextval('"+source+".shared'::regclass) PRIMARY KEY,note text)",
                "CREATE TABLE "+source+".b(id bigint DEFAULT nextval('"+source+".shared'::regclass) PRIMARY KEY,note text)",
                "INSERT INTO "+source+".a VALUES("+(descending?-7:5)+",'a')",
                "INSERT INTO "+source+".b VALUES("+(descending?-100:100)+",'b')");
        if(owned)sql("ALTER SEQUENCE "+source+".shared OWNED BY "+source+".a.id");
    }
    private void createTarget(boolean rejectB)throws Exception {
        sql("CREATE SEQUENCE "+target+".existing_shared AS bigint START WITH 5 INCREMENT BY 3 MINVALUE 1 MAXVALUE 10000",
                "CREATE TABLE "+target+".copy_a(key_id bigint DEFAULT nextval('"+target+".existing_shared'::regclass) PRIMARY KEY,note text)",
                "CREATE TABLE "+target+".copy_b(key_id bigint DEFAULT nextval('"+target+".existing_shared'::regclass) PRIMARY KEY"+(rejectB?" CHECK(key_id<50)":"")+",note text)",
                "ALTER SEQUENCE "+target+".existing_shared OWNED BY "+target+".copy_a.key_id");
    }
    private MigrationRequest request(boolean insert) {
        var request=new MigrationRequest();var input=new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.POSTGRESQL);input.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        input.setUrl(url());input.setUsername(user());input.setPassword(password());input.setSourceSchema(source);input.setSourceIncludes("a,b");
        input.setPoolSize(2);input.setFetchSize(1);input.setRegexTableMapper(List.of(new PatternMapper("^(a|b)$","copy_$1")));
        input.setRegexColumnMapper(List.of(new PatternMapper("^id$","key_id")));request.getSource().add(input);
        var output=request.getTarget();output.setTargetProductType(ProductTypeEnum.POSTGRESQL);output.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        output.setUrl(url());output.setUsername(user());output.setPassword(password());output.setTargetSchema(target);output.setPoolSize(2);
        output.setPreserveIdentityValues(true);output.setExclusiveTargetOwnership(true);output.setSyncDefaultVal(false);output.setWriterEngineInsert(insert);
        return request;
    }
    private String targetSequence()throws Exception {
        try(var connection=open();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT s.relname FROM pg_depend dep JOIN pg_attrdef d ON d.oid=dep.objid JOIN pg_class t ON t.oid=d.adrelid JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_class s ON s.oid=dep.refobjid AND s.relkind='S' WHERE dep.classid='pg_attrdef'::regclass AND dep.refclassid='pg_class'::regclass AND n.nspname='"+target+"' LIMIT 1")){assertTrue(rows.next());return rows.getString(1);}
    }
    private static long number(String sql)throws Exception{try(var connection=open();var statement=connection.createStatement();var row=statement.executeQuery(sql)){assertTrue(row.next());return row.getLong(1);}}
    private static void sql(String... values)throws Exception{try(var connection=open();var statement=connection.createStatement()){for(String value:values)statement.execute(value);}}
    private static Connection open()throws SQLException{return DriverManager.getConnection(url(),user(),password());}
    private static String url(){return System.getProperty("datahub.test.postgres.url");}
    private static String user(){return System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(){return System.getProperty("datahub.test.postgres.password","");}
}
