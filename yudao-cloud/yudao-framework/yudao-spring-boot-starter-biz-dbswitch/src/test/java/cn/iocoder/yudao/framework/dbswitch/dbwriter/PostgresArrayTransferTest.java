package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGStatement;
import org.postgresql.jdbc.PgArray;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class PostgresArrayTransferTest {
    private static final List<String> FIELDS = List.of("id", "texts", "numbers", "integers", "flags");

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualInsertPreservesDetachedTextAndBinaryArraysAfterSourceConnectionCloses(boolean binary) throws Exception {
        var schema = "array_transfer_" + UUID.randomUUID().toString().replace("-", "");
        try (var runtime = DbSwitchRuntime.standalone(); var pool = pool(runtime); var control = pool.getConnection()) {
            sql(control, "CREATE SCHEMA " + schema);
            try {
                sql(control, "CREATE TABLE " + schema + ".source(id integer PRIMARY KEY,texts text[],numbers numeric[],integers integer[],flags boolean[])");
                sql(control, "CREATE TABLE " + schema + ".target(LIKE " + schema + ".source INCLUDING ALL)");
                sql(control, "INSERT INTO " + schema + ".source VALUES(1,NULL,NULL,NULL,NULL),(2,'{}','{}','{}','{}')");
                try (var insert = control.prepareStatement("INSERT INTO " + schema + ".source VALUES(3,?,?,?,?)")) {
                    Object[][] values = {{null,"","NULL","null","中文","a\"b","a\\b","{x}","a,b"," spaced ","\n"},
                            {null,new BigDecimal("1234567890123456789012345678.12345678"),new BigDecimal("0.00000001"),new BigDecimal("-1.20")},
                            {Integer.MIN_VALUE,0,null,Integer.MAX_VALUE}, {true,false,null}};
                    String[] names = {"text","numeric","int4","bool"}; List<java.sql.Array> allocated = new ArrayList<>();
                    try {
                        for (int i=0;i<4;i++) {var a=control.createArrayOf(names[i],values[i]);allocated.add(a);insert.setArray(i+1,a);}
                        assertEquals(1,insert.executeUpdate());
                    } finally {for(var a:allocated)a.free();}
                }
                List<Object[]> detached = new ArrayList<>();
                try (var source = pool.getConnection(); var query = source.prepareStatement("SELECT * FROM " + schema + ".source ORDER BY id")) {
                    query.unwrap(PGStatement.class).setPrepareThreshold(binary ? -1 : 0);
                    try (var result = query.executeQuery()) {
                        while (result.next()) {
                            Object[] row = new Object[FIELDS.size()];
                            for(int i=0;i<row.length;i++)row[i]=result.getObject(i+1);
                            if (((Number)row[0]).intValue()==3) assertEquals(binary, ((PgArray)row[1]).isBinary());
                            detached.add(BatchValueConverter.convert(List.<Object[]>of(row), FIELDS.size()).getFirst());
                        }
                    }
                }
                assertNull(detached.get(0)[1]);
                assertTrue(((JdbcArrayValue)detached.get(1)[1]).elements().isEmpty());
                var writer = runtime.writer(pool,ProductTypeEnum.POSTGRESQL,WriteMode.INSERT);
                writer.prepareWrite(schema,"target",FIELDS);
                var receipt=writer.writeWithReceipt(FIELDS,detached);
                assertEquals(3,receipt.acknowledgedRows());
                // Ordinary PG18 INSERT is supported without the separately proven PG16 commit
                // witness. Keep its absence explicit rather than expanding receipt certification.
                int serverMajor=control.getMetaData().getDatabaseMajorVersion();
                if(serverMajor==16)assertNotNull(receipt.receipt());
                else assertNull(receipt.receipt(),"Transaction witness remains restricted to PostgreSQL 16");
                try(var stmt=control.createStatement();var result=stmt.executeQuery("SELECT count(*) FROM ((SELECT * FROM " + schema
                        + ".source EXCEPT SELECT * FROM " + schema + ".target) UNION ALL (SELECT * FROM " + schema
                        + ".target EXCEPT SELECT * FROM " + schema + ".source)) difference")) {
                    assertTrue(result.next());assertEquals(0,result.getInt(1));
                }
                assertEquals(Arrays.asList(null,"","NULL","null","中文","a\"b","a\\b","{x}","a,b"," spaced ","\n"),
                        ((JdbcArrayValue)detached.get(2)[1]).elements());
            } finally {sql(control,"DROP SCHEMA " + schema + " CASCADE");}
        }
    }

    @Test void arrayCopyRejectsWholeBatchBeforeBorrowingAWriteConnection() throws Exception {
        var schema="array_copy_"+UUID.randomUUID().toString().replace("-","");
        try(var runtime=DbSwitchRuntime.standalone();var pool=pool(runtime);var control=pool.getConnection()) {
            sql(control,"CREATE SCHEMA "+schema);
            try {
                sql(control,"CREATE TABLE "+schema+".target(id integer PRIMARY KEY,items varchar(10)[])");
                sql(control,"INSERT INTO "+schema+".target VALUES(1,ARRAY['retain'])");
                DataSource measured=mock(DataSource.class);when(measured.getConnection()).thenAnswer(i->pool.getConnection());
                var writer=runtime.writer(measured,ProductTypeEnum.POSTGRESQL,WriteMode.COPY);
                // The finite COPY profile still refuses bounded varchar ARRAY during preparation.
                assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                        ()->writer.prepareWrite(schema,"target",List.of("id","items")));
                try(var statement=control.createStatement();var result=statement.executeQuery("SELECT id,items[1] FROM "+schema+".target")) {
                    assertTrue(result.next());assertEquals(1,result.getInt(1));assertEquals("retain",result.getString(2));assertFalse(result.next());
                }
                // Preserve the whole-batch guarantee too: an ARRAY payload for an admitted scalar
                // layout fails before borrowing a write connection, including a valid earlier row.
                sql(control,"CREATE TABLE "+schema+".scalar_target(id integer PRIMARY KEY,items text)");
                sql(control,"INSERT INTO "+schema+".scalar_target VALUES(1,'retain')");
                writer.prepareWrite(schema,"scalar_target",List.of("id","items"));clearInvocations(measured);
                var array=new JdbcArrayValue("text",Types.VARCHAR,Arrays.asList(null,"","NULL","中文"));
                assertThrows(BatchRejectedException.class,()->writer.writeWithReceipt(List.of("id","items"),
                        List.of(new Object[]{2,null},new Object[]{3,array})));
                verify(measured,never()).getConnection();
                try(var statement=control.createStatement();var result=statement.executeQuery("SELECT id,items FROM "+schema+".scalar_target")) {
                    assertTrue(result.next());assertEquals(1,result.getInt(1));assertEquals("retain",result.getString(2));assertFalse(result.next());
                }
            }finally{sql(control,"DROP SCHEMA "+schema+" CASCADE");}
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualNonDefaultBoundsAndMultidimensionalArraysFailClosed(boolean binary) throws Exception {
        try(var runtime=DbSwitchRuntime.standalone();var pool=pool(runtime);var connection=pool.getConnection()) {
            for(String value:List.of("[0:1]={4,5}","{{1,2},{3,4}}")) {
                try(var statement=connection.prepareStatement("SELECT CAST(? AS integer[])")) {
                    statement.unwrap(PGStatement.class).setPrepareThreshold(binary?-1:0);statement.setString(1,value);
                    try(var result=statement.executeQuery()) {
                        assertTrue(result.next());var array=result.getArray(1);
                        assertEquals(binary,((PgArray)array).isBinary());
                        var failure=assertThrows(IllegalArgumentException.class,()->JdbcValueConversions.normalize(array));
                        assertNull(failure.getCause());
                    }
                }
            }
        }
    }

    private static com.zaxxer.hikari.HikariDataSource pool(DbSwitchRuntime runtime) {
        var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        properties.setUrl(System.getProperty("datahub.test.postgres.url"));
        properties.setUsername(System.getProperty("datahub.test.postgres.user","dataforge_test"));
        properties.setPassword(System.getProperty("datahub.test.postgres.password",""));properties.setPoolSize(3);
        return DataSourceUtils.createTargetDataSource(properties,null,null,null,runtime.drivers().runtimes(),runtime.options());
    }
    private static void sql(Connection connection,String sql)throws SQLException{try(var statement=connection.createStatement()){statement.execute(sql);}}
}
