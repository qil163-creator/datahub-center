package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact names and native domains through the public DM metadata and typed DDL APIs. */
@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.dm.url",matches="jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named="datahub.test.dm.driver",matches=".+")
@EnabledIfEnvironmentVariable(named="DATAHUB_DM_TEST_PASSWORD",matches=".+")
class DmMetadataDmTest {
    private ConfiguredJdbcDrivers drivers;private DbSwitchRuntime runtime;private Connection connection;
    private AbstractDatabase database;private String schema;private boolean created;
    @BeforeEach void setup()throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.dm.driver")).toRealPath();var spec=new DbSwitchProperties.Driver();spec.setPath(jar.toString());
        spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM,spec));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        connection=runtime.openConnection(ProductTypeEnum.DM,null,System.getProperty("datahub.test.dm.url"),"SYSDBA",System.getenv("DATAHUB_DM_TEST_PASSWORD"));
        database=runtime.products().metadata(ProductTypeEnum.DM);schema="META_DM_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        sql("CREATE SCHEMA "+q(schema));created=true;
    }
    @AfterEach void cleanup()throws Exception {
        try{if(connection!=null)try(var owned=connection){if(created)sql("DROP SCHEMA "+q(schema)+" CASCADE");}}
        finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}
    }
    @Test void quotedNamesSurviveMetadataCreateFormattingAndPrimaryKeyAssembly()throws Exception {
        String source="source\"';(, rows",target="target\"';(, rows",key="id\"';(, key",note="note\"';(, value";
        sql("CREATE TABLE "+t(source)+"("+q(key)+" BIGINT NOT NULL,"+q(note)+" VARCHAR(80),PRIMARY KEY("+q(key)+"))");
        var columns=database.queryTableColumnMeta(connection,schema,source);
        assertEquals(List.of(key,note),columns.stream().map(ColumnDescription::getFieldName).toList());
        var queryColumns=database.querySelectSqlColumnMeta(connection,"SELECT "+q(note)+" FROM "+t(source));assertEquals(note,queryColumns.getFirst().getFieldName());
        sql(GenerateSqlUtils.getDDLCreateTableSQL(database,columns,List.of(key),schema,target,false));
        assertEquals(List.of(key,note),database.queryTableColumnMeta(connection,schema,target).stream().map(ColumnDescription::getFieldName).toList());
        try(var keys=connection.getMetaData().getPrimaryKeys(null,schema,target)){assertTrue(keys.next());assertEquals(key,keys.getString("COLUMN_NAME"));assertFalse(keys.next());}
    }
    @Test void commentLiteralsRetainApostrophesBackslashesSemicolonsAndQuotes()throws Exception {
        String table="table\"'; name",column="column\"'; name",tableComment="表'\\\";注释",columnComment="列'\\\";注释";
        sql("CREATE TABLE "+t(table)+"("+q(column)+" VARCHAR(80))");
        var td=new TableDescription();td.setSchemaName(schema);td.setTableName(table);td.setRemarks(tableComment);
        var cd=new ColumnDescription();cd.setFieldName(column);cd.setRemarks(columnComment);
        for(String ddl:database.getTableColumnCommentDefinition(td,List.of(cd)))sql(ddl);
        assertEquals(tableComment,scalar("SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER=? AND TABLE_NAME=?",schema,table));
        // DM ALL_COL_COMMENTS.OWNER is the authorization user; SCHEMA_NAME identifies the physical schema.
        assertEquals(columnComment,scalar("SELECT COMMENTS FROM ALL_COL_COMMENTS WHERE SCHEMA_NAME=? AND TABLE_NAME=? AND COLUMN_NAME=?",schema,table,column));
    }
    @Test void getTableAndViewDdlBindsQuotedNamesAsValues()throws Exception {
        String table="table'\"; ddl",view="view'\"; ddl";sql("CREATE TABLE "+t(table)+"(ID BIGINT)");sql("CREATE VIEW "+t(view)+" AS SELECT ID FROM "+t(table));
        String tableDdl=database.getTableDDL(connection,schema,table),viewDdl=database.getViewDDL(connection,schema,view);
        assertNotNull(tableDdl);assertTrue(tableDdl.contains(q(table)));assertNotNull(viewDdl);assertTrue(viewDdl.contains(q(view)));
        assertEquals("ID",database.queryTableColumnMeta(connection,schema,table).getFirst().getFieldName());
    }
    @Test void schemaLiteralAndCommentJoinCannotLeakAnotherSchemasSameTable()throws Exception {
        String other=schema+"'\";",table="same_table",comment="other schema' comment";sql("CREATE SCHEMA "+q(other));
        try {
            sql("CREATE TABLE "+t(table)+"(ID BIGINT)");sql("COMMENT ON TABLE "+t(table)+" IS 'wrong owner'");
            sql("CREATE TABLE "+q(other)+'.'+q(table)+"(ID BIGINT)");sql("COMMENT ON TABLE "+q(other)+'.'+q(table)+" IS '"+comment.replace("'","''")+"'");
            var tables=database.queryTableList(connection,other);assertEquals(1,tables.size());assertEquals(table,tables.getFirst().getTableName());
            assertEquals(other,tables.getFirst().getSchemaName());assertEquals(comment,tables.getFirst().getRemarks());
        }finally{sql("DROP SCHEMA "+q(other)+" CASCADE");}
    }
    @Test void createPreservesDecimalScaleZeroAndNativeFloatingPointAndTimestampDomains()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID BIGINT PRIMARY KEY,N18 DECIMAL(18,0),N38 DECIMAL(38,0),AMOUNT DECIMAL(38,18),D DOUBLE PRECISION,R REAL,T0 TIMESTAMP(0),T3 TIMESTAMP(3),T9 TIMESTAMP(9))");
        cloneTable("S","T");assertEquals(catalog("S"),catalog("T"));
        sql("INSERT INTO "+t("S")+" VALUES(1,999999999999999999,99999999999999999999999999999999999999,-0.000000000000000001,1.234567890123456E100,1.234567E-20,TIMESTAMP '2026-10-03 01:02:03',TIMESTAMP '2026-10-03 01:02:03.123',TIMESTAMP '2026-10-03 01:02:03.123456789')");
        sql("INSERT INTO "+t("T")+" SELECT * FROM "+t("S"));
        try(var s=connection.createStatement();var sr=s.executeQuery("SELECT * FROM "+t("S"));var ts=connection.createStatement();var tr=ts.executeQuery("SELECT * FROM "+t("T"))) {
            assertTrue(sr.next());assertTrue(tr.next());for(int i=1;i<=9;i++)assertEquals(sr.getObject(i),tr.getObject(i),"exact column "+i);
        }
    }
    @Test void timeIsNotConvertedToTimestampAndRetainsItsFractionalScale()throws Exception {
        sql("CREATE TABLE "+t("S")+"(ID BIGINT PRIMARY KEY,V TIME(6))");cloneTable("S","T");assertEquals(catalog("S"),catalog("T"));
        sql("INSERT INTO "+t("S")+" VALUES(1,TIME '01:02:03.123456')");sql("INSERT INTO "+t("T")+" SELECT * FROM "+t("S"));
        assertEquals(scalar("SELECT CAST(V AS VARCHAR(40)) FROM "+t("S")),scalar("SELECT CAST(V AS VARCHAR(40)) FROM "+t("T")));
    }
    private void cloneTable(String source,String target)throws Exception {sql(GenerateSqlUtils.getDDLCreateTableSQL(database,database.queryTableColumnMeta(connection,schema,source),List.of("ID"),schema,target,false));}
    private List<List<Object>> catalog(String table)throws Exception {
        var result=new ArrayList<List<Object>>();try(var s=connection.createStatement();var r=s.executeQuery("SELECT * FROM "+t(table)+" WHERE 1=2")) {
            var md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++)result.add(List.of(md.getColumnName(i),md.getColumnType(i),md.getColumnTypeName(i),md.getPrecision(i),md.getScale(i)));
        }return result;
    }
    private String scalar(String sql,String...parameters)throws SQLException {try(var p=connection.prepareStatement(sql)){p.setQueryTimeout(15);for(int i=0;i<parameters.length;i++)p.setString(i+1,parameters[i]);try(var r=p.executeQuery()){assertTrue(r.next());String value=r.getString(1);assertFalse(r.next());return value;}}}
    private void sql(String sql)throws SQLException {try(var s=connection.createStatement()){s.setQueryTimeout(15);s.execute(sql);}}
    private String t(String name){return q(schema)+'.'+q(name);}
    private static String q(String value){return '"'+value.replace("\"","\"\"")+'"';}
}
