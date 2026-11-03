package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumIndexes;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** GP coordinator and segments exercise real index enforcement and transaction rollback. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.greenplum.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres\\?sslmode=disable")
@EnabledIfEnvironmentVariable(named="DATAHUB_GREENPLUM_TEST_PASSWORD",matches=".+")
class GreenplumIndexesGreenplumTest {
    private Connection control;
    private DataSource dataSource;
    private String schema;
    private final java.util.concurrent.atomic.AtomicReference<String> uniqueBuildFailure=new java.util.concurrent.atomic.AtomicReference<>();
    @BeforeEach void setup()throws Exception {
        dataSource=new DriverManagerDataSource(url(),"gpadmin",password());control=dataSource.getConnection();
        assertTrue(single("SELECT version()").contains("Greenplum Database 7."));
        schema="dbswitch_gpix_"+UUID.randomUUID().toString().replace("-","");sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {if(control!=null)try(var owned=control){if(schema!=null)sql("DROP SCHEMA IF EXISTS "+q(schema)+" CASCADE");}}

    @Test void publicFullAndDiffPreserveBtreeUniqueExpressionsPredicatesAndCoveringIndexes()throws Exception {
        create("source");
        sql("CREATE INDEX ordered_index ON "+table("source")+"(note DESC NULLS LAST,id ASC NULLS FIRST) INCLUDE(amount) WITH(fillfactor=83)");
        sql("COMMENT ON INDEX "+table("ordered_index")+" IS "+GreenplumNativeTables.literal("quoted ' index \\ comment"));
        sql("CREATE UNIQUE INDEX unique_index ON "+table("source")+"(id,note)");
        sql("CREATE INDEX expression_index ON "+table("source")+"(lower(note),(amount+1) DESC) WHERE amount>0 AND note IS NOT NULL");
        sql("CREATE INDEX pattern_index ON "+table("source")+"(note text_pattern_ops)");
        sql("CREATE INDEX c_index ON "+table("source")+"(note COLLATE \"C\" DESC NULLS FIRST)");
        sql("INSERT INTO "+table("source")+" VALUES(1,'AbC',12.25000),(2,NULL,-1),(3,'x',0)");
        run(false);
        assertEquals(semantic("source"),semantic("target"));
        assertEquals(data("source"),data("target"));
        sql("UPDATE "+table("source")+" SET note='Changed',amount=23.125 WHERE id=1");
        sql("DELETE FROM "+table("source")+" WHERE id=2");
        sql("INSERT INTO "+table("source")+" VALUES(4,'new',2)");
        run(true);assertEquals(data("source"),data("target"));assertEquals(semantic("source"),semantic("target"));
        assertEquals("changed",single("SELECT lower(note) FROM "+table("target")+" WHERE amount>0 AND id=1"));
        String ddl=GreenplumNativeTables.ddl(control,schema,"target",30);
        assertTrue(ddl.contains("CREATE UNIQUE INDEX"));assertTrue(ddl.contains("NULLS LAST"));
    }

    @Test void uniqueIndexActuallyEnforcesUniquenessWithoutAConstraint()throws Exception {
        sql("CREATE TABLE "+table("source")+"(id BIGINT,note TEXT) DISTRIBUTED BY(id)");
        sql("CREATE UNIQUE INDEX uniq ON "+table("source")+"(id)");
        plan("source","target",Map.of("id","id","note","note"),false).execute();
        sql("INSERT INTO "+table("target")+" VALUES(7,'first')");
        var error=assertThrows(SQLException.class,()->sql("INSERT INTO "+table("target")+" VALUES(7,'duplicate')"));
        assertEquals("23505",error.getSQLState());assertEquals("first",single("SELECT note FROM "+table("target")));
    }

    @Test void columnMappingRewritesOnlyAstReferencesAndPreservesLiteralText()throws Exception {
        create("source");
        sql("CREATE INDEX expr ON "+table("source")+"(lower(note),(amount+1)) INCLUDE(id) WHERE note<>'note'::text AND amount>0");
        var mapping=Map.of("id","new id","note","new\"note","amount","renamed_amount");
        var original=indexes("source");plan("source","mapped",mapping,false).execute();
        assertEquals(GreenplumIndexes.remap(original,mapping).stream().map(GreenplumIndexes::semantic).toList(),semantic("mapped"));
        sql("INSERT INTO "+table("mapped")+" VALUES(1,'AbC',3.125)");
        assertEquals("abc",single("SELECT lower("+q("new\"note")+") FROM "+table("mapped")));
        assertTrue(indexes("mapped").getFirst().predicate().contains("'note'"));
    }

    @Test void schemaLocalReplacementFunctionCannotMasqueradeAsBuiltin()throws Exception {
        create("source");create("target");sql("INSERT INTO "+table("target")+" VALUES(7,'preserve',1)");
        sql("CREATE FUNCTION "+table("lower")+"(text) RETURNS text LANGUAGE SQL IMMUTABLE AS 'SELECT $1'");
        sql("CREATE INDEX expr ON "+table("source")+"("+table("lower")+"(note))");
        sql("CREATE OR REPLACE FUNCTION "+table("lower")+"(text) RETURNS text LANGUAGE SQL IMMUTABLE AS 'SELECT ''replaced''::text'");
        assertThrows(RuntimeException.class,()->plan("source","target",identity(),true));
        assertEquals("preserve",single("SELECT note FROM "+table("target")));
    }

    @Test void unknownOperatorClassIsRejectedBeforeTargetDrop()throws Exception {
        create("source");create("target");sql("INSERT INTO "+table("target")+" VALUES(7,'preserve',1)");
        sql("CREATE OPERATOR CLASS "+table("custom_bigint")+" FOR TYPE bigint USING btree AS "
                +"OPERATOR 1 <(bigint,bigint),OPERATOR 2 <=(bigint,bigint),OPERATOR 3 =(bigint,bigint),"
                +"OPERATOR 4 >=(bigint,bigint),OPERATOR 5 >(bigint,bigint),FUNCTION 1 btint8cmp(bigint,bigint)");
        sql("CREATE INDEX custom ON "+table("source")+"(id "+table("custom_bigint")+")");
        assertThrows(RuntimeException.class,()->plan("source","target",identity(),true));
        assertEquals("preserve",single("SELECT note FROM "+table("target")));
    }

    @Test void indexChangedAfterPlanningInvalidatesReplacement()throws Exception {
        create("source");create("target");sql("CREATE INDEX old_target ON "+table("target")+"(note)");
        sql("INSERT INTO "+table("target")+" VALUES(7,'preserve',1)");
        var plan=plan("source","target",identity(),true);
        sql("ALTER INDEX "+table("old_target")+" SET(fillfactor=87)");
        assertThrows(RuntimeException.class,plan::execute);assertEquals("preserve",single("SELECT note FROM "+table("target")));
    }

    @Test void indexDefinitionChangesTheFrozenPlanDigest()throws Exception {
        create("source");sql("CREATE INDEX original ON "+table("source")+"(note)");
        var before=plan("source","target",identity(),false);
        sql("ALTER INDEX "+table("original")+" SET(fillfactor=81)");
        var after=plan("source","target",identity(),false);
        assertNotEquals(before.digest(),after.digest());
        after.execute();assertEquals(semantic("source"),semantic("target"));
    }

    @Test void unprovedBackslashLiteralSemanticsCannotReachTargetReplacement()throws Exception {
        create("source");create("target");sql("INSERT INTO "+table("target")+" VALUES(7,'preserve',1)");
        sql("CREATE INDEX partial ON "+table("source")+"(id) WHERE note<>"+GreenplumNativeTables.literal("literal\\note"));
        assertThrows(RuntimeException.class,()->plan("source","target",identity(),true));
        assertEquals("preserve",single("SELECT note FROM "+table("target")));
    }

    @Test void targetIndexNameCollisionNeverSkipsRequiredIndexOrDropsOldTarget()throws Exception {
        create("source");create("target");sql("CREATE INDEX original ON "+table("source")+"(note)");
        sql("INSERT INTO "+table("target")+" VALUES(7,'preserve',1)");
        var index=indexes("source").getFirst();String collision=GreenplumIndexes.targetName(index,schema,"target",0);
        var plan=plan("source","target",identity(),true);
        sql("CREATE TABLE "+table(collision)+"(id INT) DISTRIBUTED RANDOMLY");
        assertThrows(RuntimeException.class,plan::execute);assertEquals("preserve",single("SELECT note FROM "+table("target")));
        assertThrows(RuntimeException.class,()->plan("source","target",identity(),true));
    }

    @Test void realUniqueBuildFailureRollsBackTableReplacementAndRetainsOldData()throws Exception {
        sql("CREATE TABLE "+table("source")+"(id BIGINT,note TEXT) DISTRIBUTED BY(id)");
        sql("CREATE UNIQUE INDEX uniq ON "+table("source")+"(id)");
        sql("CREATE TABLE "+table("target")+"(id BIGINT,note TEXT) DISTRIBUTED BY(id)");
        sql("INSERT INTO "+table("target")+" VALUES(7,'preserve')");
        String oldOid=single("SELECT "+GreenplumNativeTables.literal(table("target"))+"::regclass::oid");
        var definition=GreenplumNativeTables.describe(dataSource,schema,"source",30);
        var injected=new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException {return inject(dataSource.getConnection());}
            @Override public Connection getConnection(String user,String password)throws SQLException {return getConnection();}
        };
        var plan=GreenplumNativeTables.plan(injected,definition,schema,"target",Map.of("id","id","note","note"),true,30);
        assertThrows(RuntimeException.class,plan::execute);
        assertEquals("23505",uniqueBuildFailure.get(),"The real database must reject the injected duplicate rows during unique-index creation");
        assertEquals(oldOid,single("SELECT "+GreenplumNativeTables.literal(table("target"))+"::regclass::oid"));
        assertEquals("preserve",single("SELECT note FROM "+table("target")));
    }

    private Connection inject(Connection real) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object value=invoke(real,method,args);
            if(method.getName().equals("createStatement")) {
                Statement statement=(Statement)value;
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                    if(m.getName().equals("execute") && a[0] instanceof String sql && sql.startsWith("CREATE UNIQUE INDEX")) {
                        statement.execute("INSERT INTO "+table("target")+" VALUES(1,'first'),(1,'second')");
                        try{return invoke(statement,m,a);}catch(SQLException error){uniqueBuildFailure.set(error.getSQLState());throw error;}
                    }
                    return invoke(statement,m,a);
                });
            }
            return value;
        });
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}}
    private void run(boolean diff)throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.GREENPLUM);source.setUrl(url());source.setUsername("gpadmin");source.setPassword(password());
        source.setSourceSchema(schema);source.setSourceIncludes("source");source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$","target")));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.GREENPLUM);target.setUrl(url());target.setUsername("gpadmin");target.setPassword(password());
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setChangeDataSync(diff);
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request,ignored->{},null,runtime).run();
            assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        }
    }
    private cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan plan(String source,String target,Map<String,String> mapping,boolean replace){return GreenplumNativeTables.plan(dataSource,GreenplumNativeTables.describe(dataSource,schema,source,30),schema,target,mapping,replace,30);}
    private List<GreenplumIndexes.Index> indexes(String name)throws Exception {
        var fields=new HashSet<String>();try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table(name)+" LIMIT 0")){for(int index=1;index<=rows.getMetaData().getColumnCount();index++)fields.add(rows.getMetaData().getColumnName(index));}
        return GreenplumIndexes.read(control,Long.parseLong(single("SELECT "+GreenplumNativeTables.literal(table(name))+"::regclass::oid")),fields,30);
    }
    private List<String> semantic(String name)throws Exception{return indexes(name).stream().map(GreenplumIndexes::semantic).sorted().toList();}
    private List<String> data(String name)throws Exception {var result=new ArrayList<String>();try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT id,note,amount FROM "+table(name)+" ORDER BY id")){while(rows.next())result.add(rows.getLong(1)+":"+rows.getString(2)+":"+rows.getBigDecimal(3));}return result;}
    private void create(String name)throws Exception{sql("CREATE TABLE "+table(name)+"(id BIGINT PRIMARY KEY,note TEXT,amount NUMERIC(20,5)) DISTRIBUTED BY(id)");}
    private Map<String,String> identity(){return Map.of("id","id","note","note","amount","amount");}
    private String single(String sql)throws Exception{try(var statement=control.createStatement();var rows=statement.executeQuery(sql)){assertTrue(rows.next());String value=rows.getString(1);assertFalse(rows.next());return value;}}
    private void sql(String sql)throws Exception{try(var statement=control.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);}}
    private String table(String name){return q(schema)+'.'+q(name);}
    private static String q(String name){return GreenplumNativeTables.quote(name);}
    private static String url(){return System.getProperty("datahub.test.greenplum.url");}
    private static String password(){return System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD");}
}
