package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.*;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native StarRocks 3.5.21, public cross-product FULL/PK DIFF, independent catalog and value witnesses. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationStarrocksPostgreSqlMappingTest {
    private final String schema = "srpg74_" + UUID.randomUUID().toString().replace("-", "");
    private final String source = "source_rows";
    private final String target = "target_rows";
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), options(), null);
        try (var c = open(STARROCKS); var s = c.createStatement(); var rows = s.executeQuery("SELECT current_version()")) {
            assertTrue(rows.next()); assertEquals("3.5.21-85fccf8", rows.getString(1)); assertFalse(rows.next());
        }
        sql(STARROCKS, "CREATE DATABASE " + q(STARROCKS, schema));
        sql(POSTGRESQL, "CREATE SCHEMA " + q(POSTGRESQL, schema));
    }
    @AfterEach void cleanup() throws Exception {
        try {
            sql(STARROCKS, "DROP DATABASE IF EXISTS " + q(STARROCKS, schema) + " FORCE");
            sql(POSTGRESQL, "DROP SCHEMA IF EXISTS " + q(POSTGRESQL, schema) + " CASCADE");
        } finally { if (runtime != null) runtime.close(); }
    }

    @Test void signed128NativeDomainCreatesPostgresRangeCheckAndRejectsOverflowBeforeSending() throws Exception {
        var domain=new LogicalType.Int128Type();
        sql(STARROCKS,"CREATE TABLE "+table(STARROCKS,source)+"(id BIGINT NOT NULL,v LARGEINT) PRIMARY KEY(id) "+srTail("id"),
                "INSERT INTO "+table(STARROCKS,source)+" VALUES(1,"+domain.minimum()+"),(2,"+domain.maximum()+"),(3,NULL)");
        assertEquals(TypeFidelity.EXACT,preview(STARROCKS,POSTGRESQL).fidelity());
        assertSuccess(run(request(STARROCKS,POSTGRESQL,true)));
        try(var c=open(POSTGRESQL);var statement=c.createStatement();var rows=statement.executeQuery("SELECT v FROM "+table(POSTGRESQL,target)+" ORDER BY id")) {
            assertTrue(rows.next());assertEquals(new BigDecimal(domain.minimum()),rows.getBigDecimal(1));
            assertTrue(rows.next());assertEquals(new BigDecimal(domain.maximum()),rows.getBigDecimal(1));
            assertTrue(rows.next());assertNull(rows.getBigDecimal(1));assertFalse(rows.next());
        }
        var writer=new StarrocksWriterImpl(ds(STARROCKS));writer.configure(options());
        var fields=List.of("id","v");writer.prepareWrite(schema,source,fields);
        for(var overflow:List.of(domain.minimum().subtract(java.math.BigInteger.ONE),domain.maximum().add(java.math.BigInteger.ONE))) {
            assertThrows(SQLException.class,()->sql(POSTGRESQL,"INSERT INTO "+table(POSTGRESQL,target)+" VALUES(4,"+overflow+")"));
            assertThrows(IllegalArgumentException.class,()->writer.write(fields,List.of(new Object[]{4L,BigDecimal.ONE},new Object[]{5L,new BigDecimal(overflow)})));
        }
        assertEquals(List.of(List.of("3")),canonical(STARROCKS,"SELECT COUNT(*) FROM "+table(STARROCKS,source)));
    }

    @ParameterizedTest @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void starrocksBinaryDecimalCompositeKeysKeepExactFullDifferenceAndReplay(boolean existing, boolean insert) throws Exception {
        createScalar(STARROCKS, source);
        seedScalar(STARROCKS, source);
        var request = request(STARROCKS, POSTGRESQL, insert);
        var preview = preview(STARROCKS, POSTGRESQL);
        assertEquals(TypeFidelity.EXACT, preview.fidelity(), preview::toString);
        if (existing) createFromNative(STARROCKS, POSTGRESQL, request);
        assertFullAndChanged(request, STARROCKS, POSTGRESQL);
        assertEquals(List.of("part|smallint", "id|bigint", "amount|numeric(38,18)", "flag|boolean", "payload|bytea"), pgColumns());
        assertEquals(List.of("part", "id"), keys(POSTGRESQL));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void postgresBinaryAndNumericRequireScopedLossDecisionsThenKeepActualValues(boolean existing) throws Exception {
        createScalar(POSTGRESQL, source); seedScalar(POSTGRESQL, source);
        var request = request(POSTGRESQL, STARROCKS, true);
        var preview = preview(POSTGRESQL, STARROCKS);
        assertEquals(TypeFidelity.LOSSY, preview.fidelity(), preview::toString);
        assertEquals(Set.of("NAN_NOT_REPRESENTABLE"), preview.column("amount").lossCodes());
        assertEquals(Set.of("BINARY_LENGTH_LIMIT"), preview.column("payload").lossCodes());
        // Unsupported/LOSSY admission precedes automatic creation and destructive replacement.
        assertFalse(run(request).getIfAllSuccess().get()); assertFalse(exists(STARROCKS, target));
        acknowledge(request, preview);
        if (existing) createFromNative(POSTGRESQL, STARROCKS, request);
        assertFullAndChanged(request, POSTGRESQL, STARROCKS);
        assertEquals(List.of("part|smallint(6)", "id|bigint(20)", "amount|decimal(38, 18)", "flag|tinyint(1)", "note|varchar(256)", "payload|varbinary"), starrocksColumns());
        assertEquals(List.of("part", "id"), keys(STARROCKS));
    }

    @ParameterizedTest @CsvSource({"STARROCKS,POSTGRESQL,false", "STARROCKS,POSTGRESQL,true", "POSTGRESQL,STARROCKS,false", "POSTGRESQL,STARROCKS,true"})
    void localMicrosecondDatetimeDateAndNullsRequireExplicitDomainAcknowledgement(ProductTypeEnum from, ProductTypeEnum to, boolean existing) throws Exception {
        sql(from, "CREATE TABLE " + table(from, source) + "(id BIGINT NOT NULL,day DATE,moment "
                + (from == STARROCKS ? "DATETIME) PRIMARY KEY(id) " + srTail("id") : "TIMESTAMP(6) WITHOUT TIME ZONE,PRIMARY KEY(id))"));
        sql(from, "INSERT INTO " + table(from, source) + " VALUES(1,'1900-01-01','1900-01-01 01:02:03.123456'),(2,NULL,NULL),"
                + "(3,'9999-12-31','9999-12-31 23:59:59.999999')");
        var request = request(from, to, true); var preview = preview(from, to);
        assertEquals(TypeFidelity.LOSSY, preview.fidelity(), preview::toString);
        assertFalse(run(request).getIfAllSuccess().get()); assertFalse(exists(to, target));
        acknowledge(request, preview); if (existing) createFromNative(from, to, request);
        assertSuccess(run(request)); assertEquals(temporal(from, source), temporal(to, target));
        sql(from, "DELETE FROM " + table(from, source) + " WHERE id=1", "UPDATE " + table(from, source)
                + " SET day='1970-01-01',moment='1970-01-01 00:00:00.000001' WHERE id=2");
        difference(request); assertSuccess(run(request)); var changed = temporal(to, target);
        assertEquals(temporal(from, source), changed); var repeat = run(request); assertSuccess(repeat);
        assertEquals(0, repeat.getTableResultList().getFirst().getSyncCount().get()); assertEquals(changed, temporal(to, target));
    }

    @ParameterizedTest @CsvSource(delimiter = ';', value = {"STARROCKS;ARRAY<INT>;POSTGRESQL;VALUE_CODEC_UNSUPPORTED", "STARROCKS;CHAR(64);POSTGRESQL;TYPE_UNMAPPED", "POSTGRESQL;TIME(6);STARROCKS;TIME_TYPE_UNAVAILABLE", "POSTGRESQL;TIMESTAMP(6) WITH TIME ZONE;STARROCKS;TIME_ZONE_SEMANTICS_UNMAPPED", "POSTGRESQL;UUID;STARROCKS;UUID_TYPE_UNAVAILABLE"})
    void unsupportedNativeMappingCannotReplaceAnExistingTarget(ProductTypeEnum from, String type, ProductTypeEnum to, String reason) throws Exception {
        sql(from, "CREATE TABLE " + table(from, source) + "(id INT NOT NULL,value " + type + ")"
                + (from == STARROCKS ? " PRIMARY KEY(id) " + srTail("id") : ""));
        sql(to, "CREATE TABLE " + table(to, target) + "(keep INT NOT NULL)" + (to == STARROCKS ? " PRIMARY KEY(keep) " + srTail("keep") : ""),
                "INSERT INTO " + table(to, target) + " VALUES(77)");
        var request = request(from, to, true); request.getTarget().setTargetDrop(true);
        request.getTarget().setExclusiveTargetDdlOwnership(true); request.getTarget().setTargetTruncateData(true);
        var result = run(request); assertFalse(result.getIfAllSuccess().get());
        var rejectedPlan = result.getTableResultList().getFirst().getTypeMappingPlan();
        if (from == STARROCKS && type.startsWith("ARRAY")) {
            // The native table codec refuses this shape before the mapping preview is collected.
            assertNull(rejectedPlan);
            assertNotNull(result.getTableResultList().getFirst().getErrorMsg());
        } else { assertNotNull(rejectedPlan); assertTrue(rejectedPlan.toString().contains(reason)); }
        assertEquals(List.of(List.of("77")), canonical(to, "SELECT keep FROM " + table(to, target)));
    }

    @Test void nativeBinaryMetadataSelectsActualByteCodecForBothEmptyAndNonemptySource() throws Exception {
        createScalar(STARROCKS, source);
        var metadata = runtime.metadata(ds(STARROCKS), STARROCKS);
        var columns = metadata.queryTableColumnMetaWithNativeEvidence(schema, source);
        var binary = columns.stream().filter(c -> c.getFieldName().equals("payload")).findFirst().orElseThrow();
        assertTrue(binary.getMetadataEvidence().provenance().nativeProduct(STARROCKS));
        assertEquals("varbinary", binary.getMetadataEvidence().nativeType().value());
        assertEquals(TypeFidelity.EXACT, runtime.products().typeMappings().map(POSTGRESQL, binary, false).fidelity());
        var operator = runtime.reader(ds(STARROCKS), STARROCKS);
        try (var result = operator.queryTableData(schema, source, List.of("payload"))) {
            assertEquals(Types.VARBINARY, result.getResultset().getMetaData().getColumnType(1)); assertFalse(result.getResultset().next());
        }
        sql(STARROCKS, "INSERT INTO " + table(STARROCKS, source) + "(part,id,payload) VALUES(1,1,x'0002ff')");
        try (var result = operator.queryTableData(schema, source, List.of("payload"))) {
            assertTrue(result.getResultset().next()); assertArrayEquals(new byte[]{0,2,(byte)255}, (byte[]) result.getResultset().getObject(1));
        }
    }

    @Test void lateOversizedBinaryRefusesWholeBatchBeforeBorrowAndKeepsSentinel() throws Exception {
        createScalar(STARROCKS, target);
        sql(STARROCKS, "INSERT INTO " + table(STARROCKS, target) + "(part,id,payload) VALUES(1,77,x'0002ff')");
        AtomicInteger borrows = new AtomicInteger(); var underlying = ds(STARROCKS);
        var counting = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { borrows.incrementAndGet(); return underlying.getConnection(); }
            @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        };
        var writer = new StarrocksWriterImpl(counting); writer.configure(options()); var fields = List.of("part", "id", "payload");
        writer.prepareWrite(schema, target, fields); borrows.set(0);
        byte[] good = {0,2,(byte)255}; byte[] bad = new byte[1048577]; bad[bad.length-1] = 1;
        var rows = List.of(new Object[]{1,1L,good},new Object[]{1,2L,bad});
        assertThrows(IllegalArgumentException.class, () -> writer.write(fields, rows));
        assertEquals(0, borrows.get()); assertArrayEquals(new byte[]{0,2,(byte)255}, good); assertEquals(1,bad[bad.length-1]);
        assertEquals(List.of(List.of("77","0002ff")), canonical(STARROCKS, "SELECT id,LOWER(HEX(payload)) FROM " + table(STARROCKS,target)));
    }

    @Test void nativeBinaryMaximumIsAcceptedAndOrdinaryTinyintKeepsItsIntegerDomain() throws Exception {
        sql(STARROCKS,"CREATE TABLE "+table(STARROCKS,target)+"(id BIGINT NOT NULL,payload VARBINARY,flag BOOLEAN,ordinary TINYINT(1)) PRIMARY KEY(id) "+srTail("id"));
        var writer=new StarrocksWriterImpl(ds(STARROCKS));writer.configure(options());var fields=List.of("id","payload","flag","ordinary");
        writer.prepareWrite(schema,target,fields);
        byte[] maximum=new byte[1048576];for(int i=0;i<maximum.length;i++)maximum[i]=(byte)i;
        assertEquals(1,writer.write(fields,List.<Object[]>of(new Object[]{1L,maximum,true,127})));
        try(var c=open(STARROCKS);var s=c.createStatement();var rows=s.executeQuery("SELECT LENGTH(payload),HEX(SUBSTRING(payload,1,4)),flag,ordinary FROM "+table(STARROCKS,target))){
            assertTrue(rows.next());assertEquals(1048576,rows.getInt(1));assertEquals("00010203",rows.getString(2));assertTrue(rows.getBoolean(3));assertEquals(127,rows.getInt(4));
        }
        var operator=runtime.reader(ds(STARROCKS),STARROCKS);
        try(var result=operator.queryTableData(schema,target,List.of("payload","flag","ordinary"))){
            var rows=result.getResultset();assertEquals(Types.BOOLEAN,rows.getMetaData().getColumnType(2));assertEquals(Types.TINYINT,rows.getMetaData().getColumnType(3));
            assertTrue(rows.next());assertArrayEquals(maximum,(byte[])rows.getObject(1));assertEquals(Boolean.TRUE,rows.getObject(2));assertEquals(127,rows.getObject(3));
        }
    }

    private void assertFullAndChanged(MigrationRequest request, ProductTypeEnum from, ProductTypeEnum to) throws Exception {
        var full = run(request); assertSuccess(full); assertEquals(4, full.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        boolean note = from == POSTGRESQL;
        assertEquals(scalars(from, source, note), scalars(to, target, note));
        sql(from, "DELETE FROM " + table(from, source) + " WHERE id=-9223372036854775808", "UPDATE " + table(from, source)
                + " SET amount=-0.000000000000000001,flag=false," + (note ? "note='changed😀'," : "") + "payload=" + bytesLiteral(from,"00ff") + " WHERE id=9007199254740993",
                "INSERT INTO " + table(from, source) + " VALUES(1,9223372036854775807,99999999999999999999.999999999999999999,true," + (note ? "'终点'," : "") + bytesLiteral(from,"000102ff") + ")");
        difference(request); assertSuccess(run(request)); var changed = scalars(to, target, note); assertEquals(scalars(from, source, note), changed);
        var repeat = run(request); assertSuccess(repeat); assertEquals(0,repeat.getTableResultList().getFirst().getSyncCount().get());
        assertEquals(changed, scalars(to, target, note));
    }
    private void createScalar(ProductTypeEnum p, String name) throws Exception {
        sql(p, "CREATE TABLE " + table(p,name) + "(part SMALLINT NOT NULL,id BIGINT NOT NULL,amount DECIMAL(38,18),flag BOOLEAN," + (p == POSTGRESQL ? "note VARCHAR(64)," : "") + "payload "
                + (p == STARROCKS ? "VARBINARY) PRIMARY KEY(part,id) " + srTail("part,id") : "BYTEA,PRIMARY KEY(part,id))"));
    }
    private void seedScalar(ProductTypeEnum p, String name) throws Exception {
        int fields = p == POSTGRESQL ? 6 : 5;
        String marks = String.join(",", Collections.nCopies(4, "(" + String.join(",", Collections.nCopies(fields, "?")) + ")"));
        try (var c=open(p);var s=c.prepareStatement("INSERT INTO "+table(p,name)+" VALUES"+marks)) {
            long[] ids={Long.MIN_VALUE,9007199254740993L,0L,3L};
            for(int i=0;i<4;i++){int b=i*fields;s.setShort(b+1,(short)(i-2));s.setLong(b+2,ids[i]);
                s.setBigDecimal(b+3,i==2?null:new BigDecimal(i==0?"99999999999999999999.999999999999999999":"-0.000000000000000001"));
                if(i==2)s.setNull(b+4,Types.BOOLEAN);else s.setBoolean(b+4,i%2==0);
                if(p==POSTGRESQL)s.setString(b+5,i==2?null:i==3?"":"中文'\\\n😀"+i);
                if(i==2)s.setNull(b+fields,Types.VARBINARY);else {
                    byte[] bytes=i==3?new byte[0]:new byte[256];for(int j=0;j<bytes.length;j++)bytes[j]=(byte)j;s.setBytes(b+fields,bytes);
                }
            }s.executeUpdate();
        }
    }
    private TableTypeMappingPlan preview(ProductTypeEnum from,ProductTypeEnum to) {
        var metadata=runtime.metadata(ds(from),from);
        return runtime.products().typeMappings().plan(to,metadata.queryTableColumnMetaWithNativeEvidence(schema,source),metadata.queryTablePrimaryKeys(schema,source),LossyColumnAcceptance.rejectAll());
    }
    private void acknowledge(MigrationRequest request,TableTypeMappingPlan preview) {
        request.setTypeMappingAcknowledgements(preview.columns().stream().filter(c->c.fidelity()==TypeFidelity.LOSSY).map(c->LossyMappingAcknowledgement.forColumn(schema,target,c)).toList());
    }
    private void createFromNative(ProductTypeEnum from,ProductTypeEnum to,MigrationRequest request) throws Exception {
        var metadata=runtime.metadata(ds(from),from);var columns=metadata.queryTableColumnMetaWithNativeEvidence(schema,source);
        var options=new TypeMappingOptions(runtime.products().typeMappings(),LossyMappingAcknowledgement.acceptanceFor(request.getTypeMappingAcknowledgements(),to,schema,target));
        sql(to,GenerateSqlUtils.getDDLCreateTableSQL(runtime.products().metadata(to),columns,metadata.queryTablePrimaryKeys(schema,source),schema,target,false,options));
    }
    private MigrationRequest request(ProductTypeEnum from,ProductTypeEnum to,boolean insert) {
        var request=new MigrationRequest();var input=new SourceDataSourceProperties();
        input.setSourceProductType(from);input.setDriverClassName(from.getDriveClassName());input.setUrl(url(from));input.setUsername(user(from));input.setPassword("");
        input.setSourceSchema(schema);input.setIncludeOrExclude(1);input.setSourceTableNames(List.of(source));input.setPoolSize(1);input.setFetchSize(1);
        input.setRegexTableMapper(List.of(new PatternMapper("^"+source+"$",target)));request.getSource().add(input);
        var output=request.getTarget();output.setTargetProductType(to);output.setDriverClassName(to.getDriveClassName());output.setUrl(url(to));output.setUsername(user(to));output.setPassword("");
        output.setTargetSchema(schema);output.setPoolSize(1);output.setWriterEngineInsert(insert);return request;
    }
    private static void difference(MigrationRequest request){request.getTarget().setChangeDataSync(true);request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");}
    private DbSwitchResult run(MigrationRequest request) throws Exception {return new MigrationService(request,ignored->{},null,runtime).run();}
    private static void assertSuccess(DbSwitchResult result){assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());}
    private List<List<String>> scalars(ProductTypeEnum p,String name,boolean note)throws Exception{return canonical(p,"SELECT part,id,amount,"+(p==STARROCKS?"CAST(flag AS INT)":"CASE WHEN flag THEN 1 WHEN NOT flag THEN 0 END")+","+(note?"note,":"")+(p==STARROCKS?"LOWER(HEX(payload))":"encode(payload,'hex')")+" FROM "+table(p,name)+" ORDER BY part,id");}
    private List<List<String>> temporal(ProductTypeEnum p,String name)throws Exception{return canonical(p,"SELECT id,"+(p==STARROCKS?"CAST(day AS VARCHAR),date_format(moment,'%Y-%m-%d %H:%i:%s.%f')":"day::text,to_char(moment,'YYYY-MM-DD HH24:MI:SS.US')")+" FROM "+table(p,name)+" ORDER BY id");}
    private List<List<String>> canonical(ProductTypeEnum p,String query)throws Exception{var result=new ArrayList<List<String>>();try(var c=open(p);var s=c.createStatement();var r=s.executeQuery(query)){while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++){int type=r.getMetaData().getColumnType(i);if(type==Types.DECIMAL||type==Types.NUMERIC){var n=r.getBigDecimal(i);row.add(n==null?null:n.stripTrailingZeros().toPlainString());}else row.add(r.getString(i));}result.add(row);}}return result;}
    private List<String> pgColumns()throws Exception{return catalog(POSTGRESQL,"SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod) FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum");}
    private List<String> starrocksColumns()throws Exception{return catalog(STARROCKS,"SELECT COLUMN_NAME,LOWER(COLUMN_TYPE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION");}
    private List<String> catalog(ProductTypeEnum p,String query)throws Exception{var result=new ArrayList<String>();try(var c=open(p);var s=c.prepareStatement(query)){s.setString(1,schema);s.setString(2,target);try(var r=s.executeQuery()){while(r.next())result.add(r.getString(1)+"|"+r.getString(2));}}return result;}
    private List<String> keys(ProductTypeEnum p)throws Exception{return runtime.metadata(ds(p),p).queryTablePrimaryKeys(schema,target);}
    private boolean exists(ProductTypeEnum p,String name)throws Exception{try(var c=open(p);var s=c.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?")){s.setString(1,schema);s.setString(2,name);try(var r=s.executeQuery()){assertTrue(r.next());return r.getLong(1)==1;}}}
    private static String bytesLiteral(ProductTypeEnum p,String hex){return p==STARROCKS?"x'"+hex+"'":"decode('"+hex+"','hex')";}
    private static String srTail(String keys){return "DISTRIBUTED BY HASH("+keys+") BUCKETS 1 PROPERTIES('replication_num'='1')";}
    private static void sql(ProductTypeEnum p,String...statements)throws Exception{try(var c=open(p);var s=c.createStatement()){s.setQueryTimeout(60);for(String text:statements)s.execute(text);}}
    private String table(ProductTypeEnum p,String name){return q(p,schema)+'.'+q(p,name);}
    private static String q(ProductTypeEnum p,String name){String mark=p==STARROCKS?"`":"\"";return mark+name.replace(mark,mark+mark)+mark;}
    private static String url(ProductTypeEnum p){return System.getProperty("datahub.test."+(p==STARROCKS?"starrocks":"postgres")+".url");}
    private static String user(ProductTypeEnum p){return System.getProperty("datahub.test."+(p==STARROCKS?"starrocks":"postgres")+".user",p==STARROCKS?"root":"dataforge_test");}
    private static DriverManagerDataSource ds(ProductTypeEnum p){return new DriverManagerDataSource(url(p),user(p),"");}
    private static Connection open(ProductTypeEnum p)throws SQLException{return DriverManager.getConnection(url(p),user(p),"");}
    private static DbSwitchOptions options(){return new DbSwitchOptions(128L*1024*1024,true,false,30,0,300000,30000,0);}
}
