package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Empty, task-owned SQL Server 2022 container only; accounts and schemas are per-test fixtures. */
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url", matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
class SqlServerDatabaseSqlServerTest {
    private final IDatabaseInterface database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.SQLSERVER);
    private Connection connection;
    private Statement sql;
    private String schema, other, reader, password;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(url(), "sa", System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD"));
        assertEquals(16, connection.getMetaData().getDatabaseMajorVersion());
        sql = connection.createStatement(); sql.setQueryTimeout(15);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        schema = "s27_"+suffix; other=schema+"_other"; reader="r27_"+suffix;
        password="F!"+UUID.randomUUID()+"aZ8";
        sql.execute("CREATE SCHEMA "+quote(schema)); sql.execute("CREATE SCHEMA "+quote(other));
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if(connection!=null&&!connection.isClosed()) {
                if(!connection.getAutoCommit()){connection.rollback();connection.setAutoCommit(true);}
                for(String scope:new String[]{schema,other}) {
                    var objects = database.queryTableList(connection, scope);
                    for(var object:objects) if("VIEW".equals(object.getTableType())) sql.execute("DROP VIEW "+table(scope,object.getTableName()));
                    for(var object:objects) if(!"VIEW".equals(object.getTableType())) sql.execute("DROP TABLE "+table(scope,object.getTableName()));
                    sql.execute("DROP SCHEMA "+quote(scope));
                }
                sql.execute("IF USER_ID(N'"+reader+"') IS NOT NULL DROP USER "+quote(reader));
                sql.execute("IF SUSER_ID(N'"+reader+"') IS NOT NULL DROP LOGIN "+quote(reader));
            }
        } finally { if(sql!=null)sql.close();if(connection!=null)connection.close(); }
    }

    @ParameterizedTest @CsvSource(delimiter='|',value={"smallint|-32768|32767|5","int|-2147483648|2147483647|4","bigint|-9223372036854775808|9223372036854775807|-5"})
    void nativeSignedKeysPreserveBoundariesAndTypes(String type,String low,String high,int expected) throws Exception {
        String table=table(schema,"sample");sql.execute("CREATE TABLE "+table+"(id "+type+")");
        sql.execute("INSERT INTO "+table+" VALUES("+low+"),("+high+"),(NULL)");
        var single=database.integerKeyProjection(connection,"k","id");
        var component=database.keyComponentProjection(connection,"k","id",21);
        try(var rows=sql.executeQuery("SELECT "+single.valueSql()+", "+single.typeSql()+", "+component.valueSql()+" FROM "+table+" k ORDER BY id")){
            assertEquals(expected,single.jdbcType(rows.getMetaData(),2));
            assertTrue(rows.next());assertNull(rows.getString(1));assertNull(rows.getBytes(3));
            assertTrue(rows.next());assertEquals(low,rows.getString(1));assertArrayEquals(low.getBytes(StandardCharsets.US_ASCII),rows.getBytes(3));
            assertTrue(rows.next());assertEquals(high,rows.getString(1));assertFalse(rows.next());
        }
        assertThrows(SQLFeatureNotSupportedException.class,()->database.queryRelationFingerprint(connection,table));
    }

    @Test void generatedCommentDdlPreservesLiteralTextAndQuotedPhysicalNames() throws Exception {
        String name = "table'\";] rows", field = "field'\";] value";
        sql.execute("CREATE TABLE " + table(schema, name) + "(" + quote(field) + " INT)");
        String remark = "中文 O'Reilly \"quoted\"; DROP TABLE never_execute; -- \\ tail";
        var td = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
        td.setSchemaName(schema); td.setTableName(name); td.setRemarks(remark);
        var cd = new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription(); cd.setFieldName(field); cd.setRemarks(remark);
        for (String ddl : database.getTableColumnCommentDefinition(td, List.of(cd))) sql.execute(ddl);
        try (var statement = connection.prepareStatement("SELECT CONVERT(nvarchar(max),e.value) FROM sys.extended_properties e"
                + " JOIN sys.tables t ON e.major_id=t.object_id JOIN sys.schemas s ON t.schema_id=s.schema_id"
                + " WHERE e.class=1 AND e.name=N'MS_Description' AND s.name=? AND t.name=? ORDER BY e.minor_id")) {
            statement.setNString(1, schema); statement.setNString(2, name);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next()); assertEquals(remark, rows.getNString(1));
                assertTrue(rows.next()); assertEquals(remark, rows.getNString(1)); assertFalse(rows.next());
            }
        }
    }

    @ParameterizedTest @CsvSource(delimiter='|',value={"tinyint|255","decimal(20,0)|9","varchar(64)|'9'","nvarchar(64)|N'9'","bit|1","float|9","varbinary(32)|0x39"})
    void convertedPayloadCannotPromoteOtherNativeTypes(String type,String value) throws Exception {
        String table=table(schema,"sample");sql.execute("CREATE TABLE "+table+"(id "+type+")");sql.execute("INSERT INTO "+table+" VALUES("+value+")");
        var projection=database.integerKeyProjection(connection,"k","id");
        try(var rows=sql.executeQuery("SELECT "+projection.valueSql()+", "+projection.typeSql()+" FROM "+table+" k")){
            assertEquals(0,projection.jdbcType(rows.getMetaData(),2));assertTrue(rows.next());
        }
    }

    @Test void metadataUsesExactSchemaAndDescriptionWithoutTruncationOrPropertyMixing() throws Exception {
        String longRemark="本产品的长注释".repeat(30);
        for(String scope:new String[]{schema,other}) {
            sql.execute("CREATE TABLE "+table(scope,"sample")+"(id BIGINT NOT NULL,part INT NOT NULL,value_col NVARCHAR(100) DEFAULT N'x', PRIMARY KEY(part,id))");
            comment(scope,"sample",null,scope.equals(schema)?longRemark:"foreign-table");
            comment(scope,"sample","value_col",scope.equals(schema)?longRemark:"foreign-column");
        }
        sql.execute("CREATE VIEW "+table(schema,"sample_view")+" AS SELECT id,value_col FROM "+table(schema,"sample"));
        var tables=database.queryTableList(connection,schema);
        assertEquals(2,tables.size());assertEquals(longRemark,tables.stream().filter(x->x.getTableName().equals("sample")).findFirst().orElseThrow().getRemarks());
        assertEquals("VIEW",tables.stream().filter(x->x.getTableName().equals("sample_view")).findFirst().orElseThrow().getTableType());
        var fields=database.queryTableColumnMeta(connection,schema,"sample");
        assertEquals(List.of("id","part","value_col"),fields.stream().map(x->x.getFieldName()).toList());
        assertEquals(longRemark,fields.get(2).getRemarks());assertEquals("(N'x')",fields.get(2).getDefaultValue());
        fields.get(2).setDefaultValue(null);fields.get(2).setRemarks(null);
        database.setColumnDefaultValue(connection,schema,"sample",fields);
        assertEquals(longRemark,fields.get(2).getRemarks());assertEquals("(N'x')",fields.get(2).getDefaultValue());
        assertEquals(List.of("part","id"),database.queryTablePrimaryKeys(connection,schema,"sample"));
        assertEquals(2,database.queryTableColumnMetaOnly(connection,schema,"sample_view").size());
        assertTrue(database.queryTableList(connection,schema+"' OR 1=1 --").isEmpty());
        assertTrue(connection.getAutoCommit());assertFalse(connection.isClosed());
    }

    @Test void bracketAndQuoteNamesAreLiteralAndNoBusinessRowIsRead() throws Exception {
        String name="sample]'quoted"; sql.execute("CREATE TABLE "+table(schema,name)+"(id BIGINT, [a] INT)");
        comment(schema,name,"a","literal-comment");
        assertEquals("literal-comment",database.queryTableColumnMeta(connection,schema,name).get(1).getRemarks());
        sql.execute("CREATE VIEW "+table(schema,"error_view")+" AS SELECT 1/0 AS bad");
        assertEquals(1,database.queryTableColumnMeta(connection,schema,"error_view").size());
    }

    @Test void selectOnlyMetadataAndKeysRespectRevocationWithoutOwningCallerTransaction() throws Exception {
        String table=table(schema,"sample");sql.execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY)");sql.execute("INSERT INTO "+table+" VALUES(1)");
        sql.execute("CREATE LOGIN "+quote(reader)+" WITH PASSWORD='"+password+"',CHECK_POLICY=OFF");
        sql.execute("CREATE USER "+quote(reader)+" FOR LOGIN "+quote(reader));
        sql.execute("GRANT SELECT ON SCHEMA::"+quote(schema)+" TO "+quote(reader));
        try(var runtime=DriverManager.getConnection(url(),reader,password);var query=runtime.createStatement()){
            assertEquals(1,database.queryTableList(runtime,schema).size());assertEquals(1,database.queryTableColumnMeta(runtime,schema,"sample").size());
            assertEquals(List.of("id"),database.queryTablePrimaryKeys(runtime,schema,"sample"));
            assertThrows(SQLException.class,()->query.execute("UPDATE "+table+" SET id=2"));
            runtime.setAutoCommit(false);database.queryTableColumnMeta(runtime,schema,"sample");assertFalse(runtime.getAutoCommit());runtime.rollback();runtime.setAutoCommit(true);
            sql.execute("REVOKE SELECT ON SCHEMA::"+quote(schema)+" FROM "+quote(reader));
            assertThrows(RuntimeException.class,()->database.queryTableColumnMeta(runtime,schema,"sample"));
            assertTrue(database.queryTableList(runtime,schema).isEmpty());assertFalse(runtime.isClosed());
        }
    }

    private void comment(String scope,String table,String column,String value)throws SQLException{
        String command="EXEC sys.sp_addextendedproperty @name=N'MS_Description',@value=?,@level0type=N'SCHEMA',@level0name=?,@level1type=N'"+(table.endsWith("_view")?"VIEW":"TABLE")+"',@level1name=?"
                +(column==null?"":",@level2type=N'COLUMN',@level2name=?");
        try(var statement=connection.prepareStatement(command)){statement.setString(1,value);statement.setString(2,scope);statement.setString(3,table);if(column!=null)statement.setString(4,column);statement.execute();}
    }
    private String table(String scope,String table){return database.getQuotedSchemaTableCombination(scope,table);}
    private String quote(String name){return "["+name.replace("]","]]")+"]";}
    private static String url(){return System.getProperty("datahub.test.sqlserver.url");}
}
