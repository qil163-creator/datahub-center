package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TIDB;
import static org.junit.jupiter.api.Assertions.*;

/** Native TiDB (PD/TiKV), registered mapping, public FULL/PK DIFF, independent catalog/value witnesses. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/.*")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationTidbPostgreSqlMappingTest {
    private final String schema = "ti73_" + UUID.randomUUID().toString().replace("-", "");
    private final String source = " 原始,表`\"";
    private final String target = " 目标,表`\"";
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        try (var c = open(TIDB); var s = c.createStatement(); var rows = s.executeQuery("SELECT tidb_version()")) {
            assertTrue(rows.next());
            String version = rows.getString(1);
            assertTrue(version.startsWith("Release Version: v8.5.6\n"));
            assertTrue(version.contains("\nEdition: Community\n"));
            assertTrue(version.endsWith("Store: tikv"));
        }
        sql(TIDB, "CREATE DATABASE " + q(TIDB, schema));
        sql(POSTGRESQL, "CREATE SCHEMA " + q(POSTGRESQL, schema));
    }

    @AfterEach void cleanup() throws Exception {
        try {
            sql(TIDB, "DROP DATABASE IF EXISTS " + q(TIDB, schema));
            sql(POSTGRESQL, "DROP SCHEMA IF EXISTS " + q(POSTGRESQL, schema) + " CASCADE");
        } finally { if (runtime != null) runtime.close(); }
    }

    @ParameterizedTest @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void unsignedDomainsAndKnownDecimalKeepExactValuesThroughFullDifferenceAndReplay(boolean existing, boolean insert) throws Exception {
        sql(TIDB, "CREATE TABLE " + table(TIDB, source) + "(part SMALLINT NOT NULL,id BIGINT UNSIGNED NOT NULL,"
                + "tiny TINYINT, tiny_u TINYINT UNSIGNED, small_u SMALLINT UNSIGNED, medium_s MEDIUMINT,"
                + "medium_u MEDIUMINT UNSIGNED,int_u INT UNSIGNED, amount DECIMAL(65,30) UNSIGNED,"
                + "signed_amount DECIMAL(12,2), PRIMARY KEY(id,part))");
        String maximum = "9".repeat(35) + "." + "9".repeat(30);
        sql(TIDB, "INSERT INTO " + table(TIDB, source) + " VALUES(-32768,18446744073709551615,-128,255,65535,-8388608,16777215,4294967295,"
                + maximum + ",-9999999999.99),(32767,0,127,0,0,8388607,0,0,0.000000000000000000000000000001,9999999999.99),"
                + "(0,9007199254740993,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
        if (existing) createFromNative(TIDB, POSTGRESQL);
        var request = request(TIDB, POSTGRESQL, insert);
        assertSuccess(run(request)); assertEquals(canonical(TIDB, numericSelect(TIDB, source)), canonical(POSTGRESQL, numericSelect(POSTGRESQL, target)));
        assertEquals(List.of("part|smallint", "id|numeric(20,0)", "tiny|smallint", "tiny_u|smallint", "small_u|integer",
                "medium_s|integer", "medium_u|integer", "int_u|bigint", "amount|numeric(65,30)", "signed_amount|numeric(12,2)"), pgColumns());
        assertEquals(List.of("id", "part"), keys(POSTGRESQL));
        for (String assignment : List.of("id=-1", "tiny=128", "tiny_u=256", "small_u=65536", "medium_s=-8388609",
                "medium_u=16777216", "int_u=4294967296", "amount=-1"))
            assertThrows(SQLException.class, () -> sql(POSTGRESQL, "UPDATE " + table(POSTGRESQL, target) + " SET " + assignment));
        sql(TIDB, "DELETE FROM " + table(TIDB, source) + " WHERE id=0", "UPDATE " + table(TIDB, source)
                + " SET signed_amount=-0.01,amount=1.000000000000000000000000000001 WHERE id=9007199254740993",
                "INSERT INTO " + table(TIDB, source) + " VALUES(1,9223372036854775808,0,1,2,3,4,5,6.000000000000000000000000000007,NULL)");
        request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        assertSuccess(run(request)); var changed = canonical(POSTGRESQL, numericSelect(POSTGRESQL, target));
        assertEquals(canonical(TIDB, numericSelect(TIDB, source)), changed);
        assertSuccess(run(request)); assertEquals(changed, canonical(POSTGRESQL, numericSelect(POSTGRESQL, target)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void binaryDatetimeAndNullsFromNativeTidbKeepBytesAndMicroseconds(boolean existing) throws Exception {
        sql(TIDB, "CREATE TABLE " + table(TIDB, source) + "(id BIGINT PRIMARY KEY, amount DECIMAL(30,10),payload VARBINARY(256),"
                + "moment DATETIME(6),day DATE,note VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin)");
        sql(TIDB, "INSERT INTO " + table(TIDB, source) + " VALUES(-9223372036854775808,-12345678901234567890.0123456789,x'" + bytes()
                + "','1919-04-13 00:30:00.123456','1000-01-01','中😀 x  '),(9223372036854775807,0,x'','9999-12-31 23:59:59.999999','9999-12-31',''),(0,NULL,NULL,NULL,NULL,NULL)");
        if (existing) createFromNative(TIDB, POSTGRESQL);
        var request=request(TIDB, POSTGRESQL, true); assertSuccess(run(request));
        assertEquals(canonical(TIDB, temporalSelect(TIDB, source)), canonical(POSTGRESQL, temporalSelect(POSTGRESQL, target)));
        sql(TIDB, "DELETE FROM " + table(TIDB, source) + " WHERE id=-9223372036854775808", "UPDATE " + table(TIDB, source)
                + " SET payload=x'00ff0001',moment='2026-10-08 12:34:56.654321',note='updated 😀' WHERE id=0");
        request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        assertSuccess(run(request)); assertEquals(canonical(TIDB, temporalSelect(TIDB, source)), canonical(POSTGRESQL, temporalSelect(POSTGRESQL, target)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void postgresKnownNumericBinaryAndUnicodeAreExactOnTidbFullDifferenceReplay(boolean existing) throws Exception {
        sql(POSTGRESQL, "CREATE TABLE " + table(POSTGRESQL, source) + "(part SMALLINT NOT NULL,id BIGINT NOT NULL,amount NUMERIC(65,30),"
                + "label VARCHAR(40), payload BYTEA, flag BOOLEAN, PRIMARY KEY(id,part))");
        String maximum="9".repeat(35)+"."+"9".repeat(30);
        sql(POSTGRESQL, "INSERT INTO " + table(POSTGRESQL, source) + " VALUES(-32768,-9223372036854775808,-"+maximum
                + ",'中😀 x  ',decode('"+bytes()+"','hex'),false),(32767,9223372036854775807,"+maximum+",'',decode('','hex'),true),(0,0,NULL,NULL,NULL,NULL)");
        if (existing) createFromNative(POSTGRESQL, TIDB);
        var request=request(POSTGRESQL,TIDB,true); assertSuccess(run(request));
        assertEquals(List.of("part|smallint", "id|bigint", "amount|decimal(65,30)", "label|varchar(40)", "payload|longblob", "flag|tinyint(1)"), tidbColumns());
        assertEquals(List.of("id","part"), keys(TIDB));
        assertEquals(canonical(POSTGRESQL, postgresSelect(POSTGRESQL, source)), canonical(TIDB, postgresSelect(TIDB, target)));
        sql(POSTGRESQL,"DELETE FROM "+table(POSTGRESQL,source)+" WHERE id=-9223372036854775808", "UPDATE "+table(POSTGRESQL,source)
                +" SET amount=-0.000000000000000000000000000001,label='updated 😀',payload=decode('00ff00','hex'),flag=false WHERE id=0",
                "INSERT INTO "+table(POSTGRESQL,source)+" VALUES(1,9007199254740993,1.000000000000000000000000000001,NULL,NULL,NULL)");
        request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        assertSuccess(run(request)); var changed=canonical(TIDB,postgresSelect(TIDB,target));
        assertEquals(canonical(POSTGRESQL,postgresSelect(POSTGRESQL,source)),changed);
        assertSuccess(run(request)); assertEquals(changed,canonical(TIDB,postgresSelect(TIDB,target)));
    }

    @ParameterizedTest @CsvSource(delimiter=';', value={"TIDB;enum('x','y');POSTGRESQL;TYPE_UNMAPPED", "TIDB;time(6);POSTGRESQL;TIME_FRACTION_TRUNCATED", "POSTGRESQL;timestamp(6) with time zone;TIDB;TIME_ZONE_SEMANTICS_UNMAPPED", "POSTGRESQL;numeric;TIDB;UNBOUNDED_NUMERIC_UNMAPPED"})
    void rejectedNativeMappingPreservesExistingTargetEvenWhenOverwriteRequested(ProductTypeEnum from,String type,ProductTypeEnum to,String reason) throws Exception {
        sql(from,"CREATE TABLE "+table(from,source)+"(id INT PRIMARY KEY,value "+type+")");
        sql(to,"CREATE TABLE "+table(to,target)+"(keep INT PRIMARY KEY)","INSERT INTO "+table(to,target)+" VALUES(77)");
        var request=request(from,to,true);request.getTarget().setTargetDrop(true);request.getTarget().setExclusiveTargetDdlOwnership(true);
        var result=run(request);assertFalse(result.getIfAllSuccess().get());
        assertNotNull(result.getTableResultList().getFirst().getTypeMappingPlan());
        assertTrue(result.getTableResultList().getFirst().getTypeMappingPlan().toString().contains(reason));
        assertEquals(List.of(List.of("77")),canonical(to,"SELECT keep FROM "+table(to,target)));
    }

    private MigrationRequest request(ProductTypeEnum from,ProductTypeEnum to,boolean insert) {
        var request=new MigrationRequest();var input=new SourceDataSourceProperties();
        input.setSourceProductType(from);input.setDriverClassName(from.getDriveClassName());input.setUrl(url(from));input.setUsername(user(from));input.setPassword(password(from));
        input.setSourceSchema(schema);input.setIncludeOrExclude(1);input.setSourceTableNames(List.of(source));input.setPoolSize(1);input.setFetchSize(1);
        input.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(source)+"$",target)));request.getSource().add(input);
        var output=request.getTarget();output.setTargetProductType(to);output.setDriverClassName(to.getDriveClassName());output.setUrl(url(to));output.setUsername(user(to));output.setPassword(password(to));
        output.setTargetSchema(schema);output.setPoolSize(1);output.setWriterEngineInsert(insert);return request;
    }
    private DbSwitchResult run(MigrationRequest request) throws Exception {return new MigrationService(request,ignored->{},null,runtime).run();}
    private static void assertSuccess(DbSwitchResult result) {assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());assertEquals(TypeFidelity.EXACT,result.getTableResultList().getFirst().getTypeMappingPlan().fidelity());}
    private void createFromNative(ProductTypeEnum from,ProductTypeEnum to) throws Exception {
        var metadata=runtime.metadata(new DriverManagerDataSource(url(from),user(from),password(from)),from);
        var columns=metadata.queryTableColumnMetaWithNativeEvidence(schema,source);
        assertTrue(columns.stream().allMatch(column->column.getMetadataEvidence().provenance().nativeProduct(from)));
        for(String ddl:GenerateSqlUtils.getDDLCreateTableSQL(runtime.products().metadata(to),columns,metadata.queryTablePrimaryKeys(schema,source),schema,target,null,false,Map.of()))sql(to,ddl);
    }
    private String numericSelect(ProductTypeEnum p,String name){return "SELECT part,id,tiny,tiny_u,small_u,medium_s,medium_u,int_u,amount,signed_amount FROM "+table(p,name)+" ORDER BY id,part";}
    private String temporalSelect(ProductTypeEnum p,String name){return "SELECT id,amount,"+(p==TIDB?"LOWER(HEX(payload)),DATE_FORMAT(moment,'%Y-%m-%d %H:%i:%s.%f'),CAST(day AS CHAR)":"encode(payload,'hex'),to_char(moment,'YYYY-MM-DD HH24:MI:SS.US'),day::text")+",note FROM "+table(p,name)+" ORDER BY id";}
    private String postgresSelect(ProductTypeEnum p,String name){return "SELECT part,id,amount,label,"+(p==TIDB?"LOWER(HEX(payload)),CAST(flag AS SIGNED)":"encode(payload,'hex'),CASE WHEN flag THEN 1 WHEN NOT flag THEN 0 ELSE NULL END")+" FROM "+table(p,name)+" ORDER BY id,part";}
    private List<String> pgColumns() throws Exception{return columnStrings(POSTGRESQL,"SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod) FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum");}
    private List<String> tidbColumns() throws Exception{return columnStrings(TIDB,"SELECT COLUMN_NAME,LOWER(COLUMN_TYPE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION");}
    private List<String> columnStrings(ProductTypeEnum p,String query) throws Exception {
        var result=new ArrayList<String>();try(var c=open(p);var s=c.prepareStatement(query)){s.setString(1,schema);s.setString(2,target);try(var r=s.executeQuery()){while(r.next())result.add(r.getString(1)+"|"+r.getString(2));}}return result;
    }
    private List<String> keys(ProductTypeEnum p) throws Exception {
        return runtime.metadata(new DriverManagerDataSource(url(p),user(p),password(p)),p).queryTablePrimaryKeys(schema,target);
    }
    private static List<List<String>> canonical(ProductTypeEnum p,String query) throws Exception {
        var result=new ArrayList<List<String>>();try(var c=open(p);var s=c.createStatement();var r=s.executeQuery(query)){int count=r.getMetaData().getColumnCount();while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=count;i++) {
            // Connector/J emits 1E-30 / 0E-10 for DECIMAL; exact plain notation avoids comparing presentation.
            // Precision and scale remain independently asserted against the native catalogs.
            int type = r.getMetaData().getColumnType(i);
            if (type == Types.DECIMAL || type == Types.NUMERIC) {
                var decimal = r.getBigDecimal(i);
                row.add(decimal == null ? null : decimal.stripTrailingZeros().toPlainString());
            } else row.add(r.getString(i));
        }result.add(row);}}return result;
    }
    private static String bytes(){byte[] bytes=new byte[256];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;return HexFormat.of().formatHex(bytes);}
    private static void sql(ProductTypeEnum p,String... statements) throws Exception {try(var c=open(p);var s=c.createStatement()){s.setQueryTimeout(60);for(String statement:statements)s.execute(statement);}}
    private String table(ProductTypeEnum p,String name){return q(p,schema)+"."+q(p,name);}
    private static String q(ProductTypeEnum p,String name){String mark=p==TIDB?"`":"\"";return mark+name.replace(mark,mark+mark)+mark;}
    private static String prefix(ProductTypeEnum p){return "datahub.test."+(p==TIDB?"tidb":"postgres");}
    private static String url(ProductTypeEnum p){return System.getProperty(prefix(p)+".url");}
    private static String user(ProductTypeEnum p){return System.getProperty(prefix(p)+".user",p==TIDB?"root":"dataforge_test");}
    private static String password(ProductTypeEnum p){return System.getProperty(prefix(p)+".password","");}
    private static Connection open(ProductTypeEnum p) throws SQLException{return DriverManager.getConnection(url(p),user(p),password(p));}
}
