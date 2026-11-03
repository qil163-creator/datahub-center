package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Real FE -> BE Stream Load. NAT translation exists only in this fixture's socket factory. */
@EnabledIfSystemProperty(named="datahub.test.doris.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@Timeout(60)
class DorisStreamLoadDorisTest {
    private HikariDataSource pool; private Connection control; private String schema;
    private final Map<String, InetSocketAddress> routes=new HashMap<>();
    private final AtomicInteger backendReceipts=new AtomicInteger();
    private final AtomicInteger frontendConnections=new AtomicInteger();
    private String frontendOrigin;
    private final List<String> routeEvidence=new ArrayList<>();

    @BeforeEach void setUp()throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        pool=new HikariDataSource();pool.setJdbcUrl(System.getProperty("datahub.test.doris.url"));pool.setUsername("root");pool.setPassword("");pool.setMaximumPoolSize(3);
        control=pool.getConnection();schema="doris_stream_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE DATABASE `"+schema+"`");
        try(var s=control.createStatement();var r=s.executeQuery("SHOW FRONTENDS")){assertTrue(r.next());int port=r.getInt("HttpPort");frontendOrigin="127.0.0.1:"+port;routes.put(frontendOrigin,new InetSocketAddress("127.0.0.1",Integer.getInteger("datahub.test.doris.fe.http.port",port)));}
        try(var s=control.createStatement();var r=s.executeQuery("SHOW BACKENDS")){assertTrue(r.next());String host=r.getString("Host");int port=r.getInt("HttpPort");routes.put(host+":"+port,new InetSocketAddress("127.0.0.1",Integer.getInteger("datahub.test.doris.be.http.port",port)));}
    }
    @AfterEach void close()throws Exception {try{if(control!=null){try{if(schema!=null)sql("DROP DATABASE `"+schema+"`");}finally{control.close();}}}finally{if(pool!=null)pool.close();}}

    @Test void publicWriterUsesRealBackendReceiptAndKeepsExactValuesAndNullDistinctions()throws Exception {
        sql("CREATE TABLE "+table("select")+"(id BIGINT NOT NULL,large_value LARGEINT,amount DECIMAL(38,18),event_time DATETIME(6),note STRING) ENGINE=OLAP DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        try(var statement=control.createStatement();var result=statement.executeQuery("SHOW CREATE TABLE "+table("select"))){assertTrue(result.next());assertTrue(result.getString(2).toLowerCase(Locale.ROOT).contains("`note` text"),"The STRING/TEXT equivalence must be observed in the real catalog");}
        cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport.inspect(control,schema,"select",20);
        var fields=List.of("id","large_value","amount","event_time","note");var writer=writer("select",fields,false);
        var maximum=new BigDecimal("170141183460469231731687303715884105727");var minimum=new BigDecimal("-170141183460469231731687303715884105728");
        var amount=new BigDecimal("12345678901234567890.123456789012345678");var time=LocalDateTime.of(2026,10,3,12,34,56,123456000);
        var rows=List.of(new Object[]{1L,maximum,amount,time,"中文😀'\\\n\0"},new Object[]{2L,minimum,amount.negate(),time,""},new Object[]{3L,null,null,null,null},new Object[]{4L,BigDecimal.ZERO,BigDecimal.ZERO,time,"NULL\\N"});
        assertEquals(4,writer.writeWithReceipt(fields,rows).acknowledgedRows());assertEquals(1,backendReceipts.get());assertSame(maximum,rows.getFirst()[1]);
        try(var s=control.createStatement();var r=s.executeQuery("SELECT * FROM "+table("select")+" ORDER BY id")){
            for(var row:rows){assertTrue(r.next());assertEquals(row[0],r.getLong(1));assertEquals(row[1]==null?null:((BigDecimal)row[1]).toPlainString(),r.getString(2));BigDecimal actual=r.getBigDecimal(3);if(row[2]==null)assertNull(actual);else assertEquals(0,((BigDecimal)row[2]).compareTo(actual));assertEquals(row[3],r.getTimestamp(4)==null?null:r.getTimestamp(4).toLocalDateTime());assertEquals(row[4],r.getString(5));}assertFalse(r.next());
        }
    }

    @Test void actualBackendCommitWithLostResponseIsUnknownAndNeverReplayed()throws Exception {
        createSimple("rows");var fields=List.of("id","note");var writer=writer("rows",fields,true);
        var failure=assertThrows(UnconfirmedWriteException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{1L,"controlled-fixture"})));
        assertNull(failure.getCause());assertEquals(1,backendReceipts.get(),routeEvidence.toString());assertEquals(1,count("rows"));
        assertNotNull(failure.requestLabel());
        var replay=assertThrows(UnconfirmedWriteException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{1L,"controlled-fixture"})));
        assertEquals(failure.requestLabel(),replay.requestLabel());
        assertThrows(UnconfirmedWriteException.class,()->writer.prepareWrite(schema,"rows",fields));
        var disabled=cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults();
        writer.configure(new cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions(disabled.maxCacheBytes(),disabled.pipelineWriteEnabled(),false,disabled.queryTimeoutSeconds(),disabled.poolMinimumIdle(),disabled.poolIdleTimeoutMs(),disabled.poolValidationTimeoutMs(),disabled.poolLeakDetectionThresholdMs()));
        assertThrows(UnconfirmedWriteException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{1L,"controlled-fixture"})));
        assertEquals(1,backendReceipts.get());assertEquals(1,count("rows"));
    }

    @Test void encodedEscapesExceedBudgetBeforeBorrowingOrSending()throws Exception {
        createSimple("bounded");var fields=List.of("id","note");
        var observed=org.mockito.Mockito.spy(pool);var writer=new DorisWriterImpl(observed);
        writer.prepareWrite(schema,"bounded",fields);
        writer.configureBatchLimits(new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(10,160,128,65535,1000));
        org.mockito.Mockito.clearInvocations(observed);
        assertThrows(cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException.class,
                ()->writer.write(fields,List.<Object[]>of(new Object[]{1L,"\0".repeat(40)})));
        org.mockito.Mockito.verify(observed,org.mockito.Mockito.never()).getConnection();
        assertEquals(0,count("bounded"));assertEquals(0,backendReceipts.get());
    }

    @Test void publicCopyFactoryReturnsNativeVisibleReceiptAndRefusesDisabledTransport()throws Exception {
        createSimple("copy_factory");var fields=List.of("id","note");
        var descriptor=new cn.iocoder.yudao.framework.dbswitch.product.doris.DorisProductProvider().products().iterator().next();
        var writer=descriptor.writer(pool,cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY);
        writer.preflightRuntimeWrite();writer.prepareWrite(schema,"copy_factory",fields);
        var helper=(OlapStreamLoadHelper)ReflectionTestUtils.getField(writer,"stream");
        ReflectionTestUtils.setField(helper,"clientFactory",(Function<RequestConfig,CloseableHttpClient>)config->mappedClient(config,false));
        var receipt=writer.writeWithReceipt(fields,List.<Object[]>of(new Object[]{1L,"confirmed"}));
        assertEquals(1,receipt.acknowledgedRows());
        assertEquals(cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE,receipt.commitAcknowledgement());
        var defaults=cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults();
        writer.configure(new cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions(defaults.maxCacheBytes(),defaults.pipelineWriteEnabled(),false,defaults.queryTimeoutSeconds(),defaults.poolMinimumIdle(),defaults.poolIdleTimeoutMs(),defaults.poolValidationTimeoutMs(),defaults.poolLeakDetectionThresholdMs()));
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,writer::preflightRuntimeWrite);
        assertEquals(1,count("copy_factory"));assertEquals(1,backendReceipts.get());
    }

    @Test void preInterruptedPublicWriterDoesNotBorrowOrSubmit()throws Exception {
        createSimple("cancelled");var fields=List.of("id","note");
        var observed=org.mockito.Mockito.spy(pool);var writer=new DorisWriterImpl(observed);writer.prepareWrite(schema,"cancelled",fields);
        org.mockito.Mockito.clearInvocations(observed);
        Thread.currentThread().interrupt();
        try{assertThrows(cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{1L,"cancelled"})));}
        finally{Thread.interrupted();}
        org.mockito.Mockito.verify(observed,org.mockito.Mockito.never()).getConnection();assertEquals(0,count("cancelled"));
    }

    @Test void duplicatePhysicalRowsAreNotCollapsedByStreamLoad()throws Exception {
        createSimple("rows");var fields=List.of("id","note");var writer=writer("rows",fields,false);
        assertEquals(2,assertDoesNotThrow(()->writer.write(fields,List.of(new Object[]{1L,"same"},new Object[]{1L,"same"})),routeEvidence::toString));
        assertEquals(1,backendReceipts.get());assertEquals(2,count("rows"));
    }

    @ParameterizedTest @ValueSource(strings={"中文表","name%?#","a'b,("})
    void nativeSpecialTableNamesKeepTheirExactHttpPath(String name)throws Exception{
        createSimple(name);var fields=List.of("id","note");var writer=writer(name,fields,false);
        assertEquals(1,assertDoesNotThrow(()->writer.write(fields,List.<Object[]>of(new Object[]{1L,"exact-name"})),routeEvidence::toString));
        assertEquals(1,backendReceipts.get());assertEquals(1,count(name));
        assertEquals(name.equals("name%?#")?0:1,frontendConnections.get(),"Direct BE routing is selected before writing, never after a failed FE request");
    }

    @Test void uniqueMergeOnWriteUsesFullRowReplacementAndCompleteReceipt()throws Exception{
        sql("CREATE TABLE "+table("unique_rows")+"(id BIGINT NOT NULL,note VARCHAR(100)) ENGINE=OLAP UNIQUE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('enable_unique_key_merge_on_write'='true','replication_num'='1')");
        sql("INSERT INTO "+table("unique_rows")+" VALUES(1,'before')");var fields=List.of("id","note");var writer=writer("unique_rows",fields,false);
        assertEquals(1,writer.writeWithReceipt(fields,List.<Object[]>of(new Object[]{1L,null})).acknowledgedRows());assertEquals(1,backendReceipts.get());
        try(var s=control.createStatement();var r=s.executeQuery("SELECT id,note FROM "+table("unique_rows"))){assertTrue(r.next());assertEquals(1,r.getLong(1));assertNull(r.getString(2));assertFalse(r.next());}
    }

    private DorisWriterImpl writer(String table,List<String> fields,boolean loseReceipt) {
        var writer=new DorisWriterImpl(pool);writer.prepareWrite(schema,table,fields);
        var helper=(OlapStreamLoadHelper)ReflectionTestUtils.getField(writer,"stream");assertNotNull(helper,"JDBC fallback cannot certify Stream Load");
        Function<RequestConfig,CloseableHttpClient> factory=config->mappedClient(config,loseReceipt);
        ReflectionTestUtils.setField(helper,"clientFactory",factory);return writer;
    }
    private CloseableHttpClient mappedClient(RequestConfig config,boolean loseReceipt) {
        var socketFactory=new PlainConnectionSocketFactory(){@Override public Socket connectSocket(int timeout,Socket socket,HttpHost host,InetSocketAddress remote,InetSocketAddress local,HttpContext context)throws IOException{
            InetSocketAddress mapped=routes.get(host.getHostName()+":"+host.getPort());if(mapped==null)throw new IOException("Unknown fixture route");
            if((host.getHostName()+":"+host.getPort()).equals(frontendOrigin))frontendConnections.incrementAndGet();
            routeEvidence.add("connect "+host.toURI());
            return super.connectSocket(timeout,socket,host,mapped,local,context);
        }};
        var registry=RegistryBuilder.<ConnectionSocketFactory>create().register("http",socketFactory).build();
        var manager=new BasicHttpClientConnectionManager(registry,null,null,host->new InetAddress[]{InetAddress.getLoopbackAddress()});
        HttpResponseInterceptor interceptor=(response,context)->{
            routeEvidence.add("status "+response.getStatusLine().getStatusCode());
            if(response.getStatusLine().getStatusCode()!=200)return;
            byte[] bytes=EntityUtils.toByteArray(response.getEntity());var receipt=new ObjectMapper().readTree(bytes);
            assertEquals("Success",receipt.path("Status").asText(),"A real BE receipt is required");assertTrue(receipt.path("TxnId").asLong()>0);backendReceipts.incrementAndGet();
            if(loseReceipt)throw new IOException("controlled response loss after real backend acknowledgement");
            response.setEntity(new org.apache.http.entity.ByteArrayEntity(bytes));
        };
        return HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config).disableAutomaticRetries().disableRedirectHandling().disableContentCompression().addInterceptorLast(interceptor).build();
    }
    private void createSimple(String name)throws Exception{sql("CREATE TABLE "+table(name)+"(id BIGINT NOT NULL,note VARCHAR(100)) ENGINE=OLAP DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");}
    private long count(String name)throws Exception{try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table(name))){assertTrue(r.next());return r.getLong(1);}}
    private String table(String name){return "`"+schema+"`.`"+name.replace("`","``")+"`";}
    private void sql(String sql)throws Exception{try(var s=control.createStatement()){s.setQueryTimeout(20);s.execute(sql);}}
}
