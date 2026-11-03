package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksWriterImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Requires a local StarRocks fixture with its advertised FE/BE HTTP endpoints reachable. */
@EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@Timeout(60)
class StarrocksStreamLoadStarrocksTest {
    @Test void binaryDecodingKeepsNullEmptyBytesAndCollisionNamedColumnsDistinctFromBase64Text()throws Exception {
        String schema="sr_binary_"+UUID.randomUUID().toString().replace("-","");
        try(var source=new HikariDataSource()) {
            source.setJdbcUrl(System.getProperty("datahub.test.starrocks.url"));source.setUsername("root");source.setPassword("");source.setMaximumPoolSize(2);
            try(var connection=source.getConnection();var sql=connection.createStatement()) {
                sql.execute("CREATE DATABASE "+schema);try {
                    sql.execute("CREATE TABLE "+schema+".sample(id BIGINT NOT NULL,payload VARBINARY,__dbswitch_binary_0 VARBINARY,normal VARCHAR(99)) PRIMARY KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
                    var writer=new StarrocksWriterImpl(source);var fields=List.of("id","payload","__dbswitch_binary_0","normal");writer.prepareWrite(schema,"sample",fields);
                    assertNotNull(ReflectionTestUtils.getField(writer,"streamLoadHelper"));
                    var rows=List.of(new Object[]{1L,null,new byte[0],"AAL/"},new Object[]{2L,new byte[0],null,""},new Object[]{3L,new byte[]{0,2,(byte)255},new byte[]{(byte)255,0},"AAL/"});
                    assertEquals(3,writer.write(fields,rows));
                    try(var r=sql.executeQuery("SELECT id,payload,__dbswitch_binary_0,normal FROM "+schema+".sample ORDER BY id")) {
                        for(var expected:rows){assertTrue(r.next());assertEquals(expected[0],r.getLong(1));assertArrayEquals((byte[])expected[1],r.getBytes(2));assertArrayEquals((byte[])expected[2],r.getBytes(3));assertEquals(expected[3],r.getString(4));}assertFalse(r.next());
                    }
                }finally{sql.execute("DROP DATABASE "+schema+" FORCE");}
            }
        }
    }
    @Test
    void publicWriterStreamLoadPersistsExactDecimalsAndMicrosecondTimestamps() throws Exception {
        String schema = "sr_stream_" + UUID.randomUUID().toString().replace("-", "");
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl(System.getProperty("datahub.test.starrocks.url"));
            source.setUsername(System.getProperty("datahub.test.starrocks.user", "root"));
            source.setPassword(System.getProperty("datahub.test.starrocks.password", ""));
            source.setMinimumIdle(0); source.setMaximumPoolSize(2); source.setConnectionTimeout(10_000);
            try (var connection = source.getConnection(); var sql = connection.createStatement()) {
                sql.setQueryTimeout(20);
                sql.execute("CREATE DATABASE " + schema);
                try {
                    // StarRocks 3.5 accepts DATETIME without a display precision clause. The
                    // exact microsecond read-back below remains mandatory for this writer path.
                    sql.execute("CREATE TABLE " + schema + ".sample (id BIGINT, amount DECIMAL(30,9), event_time DATETIME)"
                            + " DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')");
                    var writer = new StarrocksWriterImpl(source);
                    var fields = List.of("id", "amount", "event_time");
                    writer.prepareWrite(schema, "sample", fields);
                    assertNotNull(ReflectionTestUtils.getField(writer, "streamLoadHelper"),
                            "A configured fixture must discover Stream Load; JDBC fallback does not validate the HTTP writer");
                    var firstAmount = new BigDecimal("123456789012345678901.123456789");
                    var secondAmount = new BigDecimal("-0.000000001");
                    var firstTime = LocalDateTime.of(2026, 1, 2, 10, 30, 0, 123456000);
                    var secondTime = LocalDateTime.of(2026, 1, 2, 10, 30, 1, 654321000);
                    var rows = List.of(new Object[]{1L, firstAmount, firstTime}, new Object[]{2L, secondAmount, secondTime});
                    assertEquals(2, writer.write(fields, rows));
                    assertEquals(2, rows.size(), "The writer must retain caller-owned records");
                    try (var result = sql.executeQuery("SELECT id, amount, event_time FROM " + schema + ".sample ORDER BY id")) {
                        assertTrue(result.next()); assertEquals(1L, result.getLong(1));
                        assertEquals(0, firstAmount.compareTo(result.getBigDecimal(2)));
                        assertEquals(firstTime, result.getTimestamp(3).toLocalDateTime());
                        assertTrue(result.next()); assertEquals(2L, result.getLong(1));
                        assertEquals(0, secondAmount.compareTo(result.getBigDecimal(2)));
                        assertEquals(secondTime, result.getTimestamp(3).toLocalDateTime());
                        assertFalse(result.next(), "The two input rows must appear exactly once");
                    }
                } finally {
                    sql.execute("DROP DATABASE IF EXISTS " + schema + " FORCE");
                }
            }
        }
    }
}
