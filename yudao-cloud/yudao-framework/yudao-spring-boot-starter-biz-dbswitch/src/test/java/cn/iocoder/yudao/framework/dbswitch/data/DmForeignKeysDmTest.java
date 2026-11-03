package cn.iocoder.yudao.framework.dbswitch.data;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.util.*;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.dm.*;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.dm.url",matches="jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.dm.driver",matches=".+")
@EnabledIfEnvironmentVariable(named="DATAHUB_DM_TEST_PASSWORD",matches=".+")
class DmForeignKeysDmTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private Connection admin;private String schema;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        admin=open();schema="FK_DM_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {try{if(admin!=null)try(var owned=admin){if(schema!=null)sql("DROP SCHEMA "+q(schema)+" CASCADE");}}finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}}


    @ParameterizedTest @ValueSource(strings={"UPDATE_NO_ACTION","UPDATE_CASCADE","UPDATE_SET_NULL","UPDATE_SET_DEFAULT","DELETE_NO_ACTION","DELETE_CASCADE","DELETE_SET_NULL","DELETE_SET_DEFAULT"})
    void nativeActionsPreserveBehaviorAndOrderedCompositeReferences(String mode)throws Exception {
        boolean update=mode.startsWith("UPDATE_");var action=DmNativeForeignKeys.Action.valueOf(mode.substring(mode.indexOf('_')+1));
        String clause=action==DmNativeForeignKeys.Action.NO_ACTION?"":" ON "+(update?"UPDATE ":"DELETE ")+action.sql();
        parent();sql("CREATE TABLE "+t("C")+"(ID INT PRIMARY KEY,X BIGINT DEFAULT 0,Y INT DEFAULT 0,CONSTRAINT FK FOREIGN KEY(X,Y) REFERENCES "+t("P")+"(B,A)"+clause+")");
        var catalog=DmNativeForeignKeys.describe(ds(),schema,"C",15);assertEquals(1,catalog.entries().size());var fk=catalog.entries().getFirst();
        assertEquals(List.of("X","Y"),fk.childColumns());assertEquals(List.of("B","A"),fk.parentColumns());assertEquals(new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(schema,"P"),fk.parent());
        assertEquals(update?action:DmNativeForeignKeys.Action.NO_ACTION,fk.onUpdate());assertEquals(update?DmNativeForeignKeys.Action.NO_ACTION:action,fk.onDelete());assertTrue(fk.enabled());
        assertEquals(action!=DmNativeForeignKeys.Action.NO_ACTION,fk.actionTriggerId()>0);assertTrue(fk.supportingIndexId()>0);assertEquals("VIRTUAL",fk.supportingIndexType());
        sql("INSERT INTO "+t("P")+" VALUES(1,10),(0,0)");sql("INSERT INTO "+t("C")+" VALUES(1,10,1)");
        String change=update?"UPDATE "+t("P")+" SET B=11 WHERE B=10":"DELETE FROM "+t("P")+" WHERE B=10";
        if(action==DmNativeForeignKeys.Action.NO_ACTION){assertThrows(SQLException.class,()->sql(change));assertEquals(List.of("10:1"),childRows());}
        else {sql(change);assertEquals(switch(action){case CASCADE->update?List.of("11:1"):List.of();case SET_NULL->List.of("null:null");case SET_DEFAULT->List.of("0:0");default->throw new AssertionError();},childRows());}
        if(action==DmNativeForeignKeys.Action.SET_DEFAULT&&!update)assertEquals("CASCADE",scalar("SELECT DELETE_RULE FROM ALL_CONSTRAINTS WHERE OWNER='"+schema+"' AND CONSTRAINT_NAME='FK'"));
    }
    @Test void disabledStateAndClosedSelectionAreExplicit()throws Exception {
        parent();sql("CREATE TABLE "+t("C")+"(X BIGINT,Y INT,CONSTRAINT FK FOREIGN KEY(X,Y) REFERENCES "+t("P")+"(B,A) DISABLE)");
        var catalog=DmNativeForeignKeys.describe(ds(),schema,"C",15);assertFalse(catalog.entries().getFirst().enabled());
        sql("INSERT INTO "+t("C")+" VALUES(123,456)");assertEquals(List.of("123:456"),childRows());
        var child=new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(schema,"C");var parent=new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(schema,"P");
        assertThrows(IllegalArgumentException.class,()->catalog.requireClosedSelection(Set.of(child)));assertDoesNotThrow(()->catalog.requireClosedSelection(Set.of(child,parent)));
    }
    @Test void quotedPhysicalNamesAndUniqueReferencedKeysStayExact()throws Exception {
        String parent="p'\";.",child="c'\";.",ref="r'\";.",field="f'\";.";
        sql("CREATE TABLE "+t(parent)+"("+q(ref)+" BIGINT UNIQUE)");sql("CREATE TABLE "+t(child)+"("+q(field)+" BIGINT,CONSTRAINT "+q("fk'\";.")+" FOREIGN KEY("+q(field)+") REFERENCES "+t(parent)+"("+q(ref)+"))");
        var fk=DmNativeForeignKeys.describe(ds(),schema,child,15).entries().getFirst();assertEquals(List.of(field),fk.childColumns());assertEquals(List.of(ref),fk.parentColumns());assertEquals(parent,fk.parent().table());
    }
    @Test void selfAndCyclicRelationsAreCapturedWithoutInventingASortOrder()throws Exception {
        sql("CREATE TABLE "+t("A")+"(ID INT PRIMARY KEY,V INT,CONSTRAINT SELFREF FOREIGN KEY(V) REFERENCES "+t("A")+"(ID))");
        sql("CREATE TABLE "+t("B")+"(ID INT PRIMARY KEY,V INT,CONSTRAINT BA FOREIGN KEY(V) REFERENCES "+t("A")+"(ID))");sql("ALTER TABLE "+t("A")+" ADD CONSTRAINT AB FOREIGN KEY(V) REFERENCES "+t("B")+"(ID)");
        var selected=Set.of(new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(schema,"A"),new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(schema,"B"));
        var a=DmNativeForeignKeys.describe(ds(),schema,"A",15);var b=DmNativeForeignKeys.describe(ds(),schema,"B",15);assertEquals(2,a.entries().size());assertEquals(1,b.entries().size());a.requireClosedSelection(selected);b.requireClosedSelection(selected);
    }
    @Test void parentDropRecreateCannotRetainTheOldDependencyWitness()throws Exception {
        parent();sql("CREATE TABLE "+t("C")+"(X BIGINT,Y INT,CONSTRAINT FK FOREIGN KEY(X,Y) REFERENCES "+t("P")+"(B,A))");var first=DmNativeForeignKeys.describe(ds(),schema,"C",15);
        sql("ALTER TABLE "+t("C")+" DROP CONSTRAINT FK");sql("DROP TABLE "+t("P"));parent();sql("ALTER TABLE "+t("C")+" ADD CONSTRAINT FK FOREIGN KEY(X,Y) REFERENCES "+t("P")+"(B,A)");
        var second=DmNativeForeignKeys.describe(ds(),schema,"C",15);assertNotEquals(first.entries().getFirst().parentIdentity(),second.entries().getFirst().parentIdentity());assertNotEquals(first.entries().getFirst().referencedIndexId(),second.entries().getFirst().referencedIndexId());
    }
    @Test void deniedNativeCatalogIsNotInterpretedAsNoForeignKeys()throws Exception {
        parent();sql("CREATE TABLE "+t("C")+"(X BIGINT,Y INT,CONSTRAINT FK FOREIGN KEY(X,Y) REFERENCES "+t("P")+"(B,A))");
        DataSource denied=new AbstractDataSource(){@Override public Connection getConnection()throws SQLException {var actual=open();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{if(m.getName().equals("prepareStatement")&&a[0] instanceof String text&&text.contains("fk.faction"))throw new SQLException("injected denied evidence","42501");try{return m.invoke(actual,a);}catch(InvocationTargetException e){throw e.getCause();}});}@Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Explicit fixture principal required");}};
        var failure=assertThrows(IllegalStateException.class,()->DmNativeForeignKeys.describe(denied,schema,"C",15));assertNull(failure.getCause());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void typedPlanRemapsRelationsAndColumnsAndKeepsEnabledState(boolean disabled)throws Exception {
        parent();sql("CREATE TABLE "+t("C")+"(X BIGINT,Y INT,CONSTRAINT FK FOREIGN KEY(X,Y) REFERENCES "+t("P")+"(B,A) ON UPDATE CASCADE ON DELETE SET NULL"+(disabled?" DISABLE":"")+")");
        String targetParent="p2'\";.",targetChild="c2'\";.",mapped="x2';.";
        var p=rel("P");var c=rel("C");var definitions=Map.of(p,DmNativeForeignKeys.describe(ds(),schema,"P",15),c,DmNativeForeignKeys.describe(ds(),schema,"C",15));
        var targets=Map.of(p,new DmForeignKeyPlan.Target(rel(targetParent),Map.of("A","AA","B","BB")),c,new DmForeignKeyPlan.Target(rel(targetChild),Map.of("X",mapped,"Y","YY")));
        var plan=DmForeignKeyPlan.compile(definitions,targets);assertEquals(plan.digest(),DmForeignKeyPlan.compile(new LinkedHashMap<>(definitions),targets).digest());assertEquals(1,plan.constraints().size());plan.assertNamesAvailable(admin,15);
        sql("CREATE TABLE "+t(targetParent)+"(AA INT,BB BIGINT,PRIMARY KEY(BB,AA))");sql("CREATE TABLE "+t(targetChild)+"("+q(mapped)+" BIGINT,YY INT)");sql(plan.constraints().getFirst().sql());
        var actual=DmNativeForeignKeys.describe(ds(),schema,targetChild,15).entries().getFirst();assertEquals(List.of(mapped,"YY"),actual.childColumns());assertEquals(List.of("BB","AA"),actual.parentColumns());assertEquals(!disabled,actual.enabled());assertEquals(DmNativeForeignKeys.Action.CASCADE,actual.onUpdate());assertEquals(DmNativeForeignKeys.Action.SET_NULL,actual.onDelete());
        assertThrows(SQLException.class,()->plan.assertNamesAvailable(admin,15));
        sql("INSERT INTO "+t(targetParent)+" VALUES(1,10)");sql("INSERT INTO "+t(targetChild)+" VALUES(10,1)");
        try{sql("UPDATE "+t(targetParent)+" SET BB=11");}catch(SQLException failure){fail("Mapped native action failed on UPDATE: vendor="+failure.getErrorCode()+", SQLState="+failure.getSQLState());}
        assertEquals(disabled?"10":"11",scalar("SELECT "+q(mapped)+" FROM "+t(targetChild)));
    }
    @Test void missingRelationColumnOrConflictingTargetCannotCompile()throws Exception {
        parent();sql("CREATE TABLE "+t("C")+"(X BIGINT,Y INT,CONSTRAINT FK FOREIGN KEY(X,Y) REFERENCES "+t("P")+"(B,A))");var p=rel("P");var c=rel("C");var pd=DmNativeForeignKeys.describe(ds(),schema,"P",15);var cd=DmNativeForeignKeys.describe(ds(),schema,"C",15);
        assertThrows(IllegalArgumentException.class,()->DmForeignKeyPlan.compile(Map.of(c,cd),Map.of(c,new DmForeignKeyPlan.Target(rel("C2"),Map.of("X","X","Y","Y")))));
        assertThrows(IllegalArgumentException.class,()->DmForeignKeyPlan.compile(Map.of(p,pd,c,cd),Map.of(p,new DmForeignKeyPlan.Target(rel("P2"),Map.of("A","A")),c,new DmForeignKeyPlan.Target(rel("C2"),Map.of("X","X","Y","Y")))));
        assertThrows(IllegalArgumentException.class,()->DmForeignKeyPlan.compile(Map.of(p,pd,c,cd),Map.of(p,new DmForeignKeyPlan.Target(rel("BOTH"),Map.of("A","A","B","B")),c,new DmForeignKeyPlan.Target(rel("BOTH"),Map.of("X","X","Y","Y")))));
    }
    @Test void targetConstraintNameCollisionFailsBeforeAnyDependentDdl()throws Exception {
        parent();sql("CREATE TABLE "+t("C")+"(X BIGINT,Y INT,CONSTRAINT FK FOREIGN KEY(X,Y) REFERENCES "+t("P")+"(B,A))");var p=rel("P");var c=rel("C");var plan=DmForeignKeyPlan.compile(Map.of(p,DmNativeForeignKeys.describe(ds(),schema,"P",15),c,DmNativeForeignKeys.describe(ds(),schema,"C",15)),Map.of(p,new DmForeignKeyPlan.Target(rel("P2"),Map.of("A","A","B","B")),c,new DmForeignKeyPlan.Target(rel("C2"),Map.of("X","X","Y","Y"))));
        sql("CREATE TABLE "+t("OCCUPIED")+"(A INT,CONSTRAINT "+q(plan.constraints().getFirst().name())+" CHECK(A>0))");assertThrows(SQLException.class,()->plan.assertNamesAvailable(admin,15));assertEquals("0",scalar("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER='"+schema+"' AND TABLE_NAME IN('P2','C2')"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void quotedFkColumnNeedsInlinePhaseBeforeAnyDeferredPlanCanExecute(boolean parentQuoted)throws Exception {
        String childField=parentQuoted?"X":"x\"x",parentField=parentQuoted?"p\"p":"ID";
        sql("CREATE TABLE "+t("P")+"("+q(parentField)+" INT PRIMARY KEY)");sql("INSERT INTO "+t("P")+" VALUES(1)");
        sql("CREATE TABLE "+t("C")+"("+q(childField)+" INT,CONSTRAINT INLINE_FK FOREIGN KEY("+q(childField)+") REFERENCES "+t("P")+"("+q(parentField)+"))");
        sql("INSERT INTO "+t("C")+" VALUES(1)");assertThrows(SQLException.class,()->sql("INSERT INTO "+t("C")+" VALUES(2)"));
        var p=rel("P");var c=rel("C");var sources=Map.of(p,DmNativeForeignKeys.describe(ds(),schema,"P",15),c,DmNativeForeignKeys.describe(ds(),schema,"C",15));
        assertThrows(IllegalArgumentException.class,()->DmForeignKeyPlan.compile(sources,Map.of(p,new DmForeignKeyPlan.Target(rel("P2"),Map.of(parentField,parentField)),c,new DmForeignKeyPlan.Target(rel("C2"),Map.of(childField,childField)))));
        assertEquals("0",scalar("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER='"+schema+"' AND TABLE_NAME IN('P2','C2')"));
        // Keep the real vendor counterexample. Rejecting the plan does not count as delivering inline migration.
        sql("CREATE TABLE "+t("DEFERRED")+"("+q(childField)+" INT)");
        var failure=assertThrows(SQLException.class,()->sql("ALTER TABLE "+t("DEFERRED")+" ADD CONSTRAINT DEFERRED_FK FOREIGN KEY("+q(childField)+") REFERENCES "+t("P")+"("+q(parentField)+")"));
        assertEquals(-2007,failure.getErrorCode());
    }
    private cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation rel(String table){return new cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation(schema,table);}
    private void parent()throws SQLException{sql("CREATE TABLE "+t("P")+"(A INT NOT NULL,B BIGINT NOT NULL,CONSTRAINT PK PRIMARY KEY(B,A))");}
    private List<String> childRows()throws SQLException{var values=new ArrayList<String>();try(var s=admin.createStatement();var r=s.executeQuery("SELECT X,Y FROM "+t("C"))){while(r.next())values.add(r.getString(1)+":"+r.getString(2));}return values;}
    private DataSource ds(){return new AbstractDataSource(){@Override public Connection getConnection()throws SQLException{return open();}@Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Explicit fixture principal required");}};}
    private Connection open()throws SQLException{return runtime.openConnection(ProductTypeEnum.DM,null,url(),"SYSDBA",password());}
    private void sql(String text)throws SQLException{try(var s=admin.createStatement()){s.setQueryTimeout(15);s.execute(text);}catch(SQLException failure){throw new SQLException("Fixture "+text.substring(0,text.indexOf(' '))+" failed; vendor="+failure.getErrorCode(),failure.getSQLState(),failure.getErrorCode());}}
    private String scalar(String text)throws SQLException{try(var s=admin.createStatement();var r=s.executeQuery(text)){assertTrue(r.next());String value=r.getString(1);assertFalse(r.next());return value;}}
    private String t(String table){return q(schema)+'.'+q(table);}private static String q(String value){return DmSqlNames.quote(value);}
    private static String url(){return System.getProperty("datahub.test.dm.url");}private static String password(){return System.getenv("DATAHUB_DM_TEST_PASSWORD");}
}
