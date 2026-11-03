package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real Doris 4.0.8 FE -> BE Stream Load into static partitions: routing, visibility, receipt states and labels. NAT translation exists only in this fixture. */
@EnabledIfSystemProperty(named="datahub.test.doris.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@Timeout(90)
class DorisPartitionStreamLoadDorisTest {
    static final String RANGE="(k DATE NOT NULL,id BIGINT NOT NULL,note VARCHAR(100)) ENGINE=OLAP UNIQUE KEY(k,id) PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('2026-01-01'),PARTITION p2 VALUES [('2026-01-01'),('2026-02-01')),PARTITION pmax VALUES LESS THAN MAXVALUE) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')";
    private HikariDataSource pool;private Connection control;private String schema;
    private final Map<String,InetSocketAddress> routes=new HashMap<>();
    private final List<String> statuses=new CopyOnWriteArrayList<>();
    private int requests;

    @BeforeEach void setUp()throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        pool=new HikariDataSource();pool.setJdbcUrl(System.getProperty("datahub.test.doris.url"));pool.setUsername("root");pool.setPassword("");pool.setMaximumPoolSize(3);
        control=pool.getConnection();schema="doris_part_stream_"+UUID.randomUUID().toString().replace("-","");sql("CREATE DATABASE `"+schema+"`");
        try(var s=control.createStatement();var r=s.executeQuery("SHOW FRONTENDS")){assertTrue(r.next());assertTrue(r.getString("Version").startsWith("doris-4.0.8"));int port=r.getInt("HttpPort");routes.put("127.0.0.1:"+port,new InetSocketAddress("127.0.0.1",Integer.getInteger("datahub.test.doris.fe.http.port",port)));}
        try(var s=control.createStatement();var r=s.executeQuery("SHOW BACKENDS")){assertTrue(r.next());String host=r.getString("Host");int port=r.getInt("HttpPort");routes.put(host+":"+port,new InetSocketAddress("127.0.0.1",Integer.getInteger("datahub.test.doris.be.http.port",port)));}
    }
    @AfterEach void close()throws Exception {try{if(control!=null){try{if(schema!=null)sql("DROP DATABASE `"+schema+"` FORCE");}finally{control.close();}}}finally{if(pool!=null)pool.close();}}

    @Test void visibleReceiptPlacesBoundaryRowsInTheirPartitionsAndIsReadableImmediately()throws Exception {
        sql("CREATE TABLE "+table("rows")+RANGE);var fields=List.of("k","id","note");var writer=writer("rows",fields,false,null);
        var rows=List.of(new Object[]{LocalDate.of(2025,12,31),1L,"p1"},new Object[]{LocalDate.of(2026,1,1),2L,"p2 lower bound"},new Object[]{LocalDate.of(2026,1,31),3L,"p2"},new Object[]{LocalDate.of(2026,2,1),4L,"pmax lower bound"});
        assertEquals(4,writer.writeWithReceipt(fields,rows).acknowledgedRows());assertEquals(List.of("Success"),statuses);
        assertEquals(Map.of("p1",1L,"p2",2L,"pmax",1L),partitions("rows"),"A VISIBLE receipt is readable without waiting");
    }
    @Test void outOfPartitionBatchFailsBeforeAnyHttpRequestAndNothingIsSent()throws Exception {
        sql("CREATE TABLE "+table("bounded")+RANGE.replace(",PARTITION pmax VALUES LESS THAN MAXVALUE",""));var fields=List.of("k","id","note");var writer=writer("bounded",fields,false,null);
        var inside=new Object[]{LocalDate.of(2026,1,31),1L,"inside"};var outside=new Object[]{LocalDate.of(2026,2,1),2L,"outside"};
        assertThrows(IllegalArgumentException.class,()->writer.write(fields,List.of(inside,outside)));assertEquals(0,requests,"Routing precedes the first HTTP request");assertEquals(0,count("bounded"));
        assertEquals(1,writer.write(fields,List.<Object[]>of(inside)));assertEquals(Map.of("p1",0L,"p2",1L),partitions("bounded"));
    }
    @Test void nullListKeysRouteToTheNullPartitionThroughStreamLoad()throws Exception {
        sql("CREATE TABLE "+table("nulls")+"(k INT NULL,id BIGINT NOT NULL,note VARCHAR(20)) ENGINE=OLAP DUPLICATE KEY(k,id) PARTITION BY LIST(k) (PARTITION p1 VALUES IN ('1'),PARTITION pn VALUES IN (NULL)) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        var fields=List.of("k","id","note");var writer=writer("nulls",fields,false,null);
        assertEquals(2,writer.write(fields,Arrays.asList(new Object[]{null,1L,"null key"},new Object[]{1,2L,"one"})));assertEquals(Map.of("p1",1L,"pn",1L),partitions("nulls"));
        assertThrows(IllegalArgumentException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{2,3L,"no partition"})));assertEquals(2,count("nulls"));
    }
    /** The server applies a label once; a second request with the same label is never an acknowledgement and never a second application. */
    @Test void reusedLabelIsRejectedByTheServerAndNeverAcknowledgedOrAppliedTwice()throws Exception {
        sql("CREATE TABLE "+table("labels")+RANGE);var fields=List.of("k","id","note");String label="dbswitch_probe_"+UUID.randomUUID().toString().replace("-","");
        var writer=writer("labels",fields,false,()->label);
        assertEquals(1,writer.write(fields,List.<Object[]>of(new Object[]{LocalDate.of(2026,1,2),1L,"first"})));
        var capture=capture();try{
            var failure=assertThrows(UnconfirmedWriteException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{LocalDate.of(2026,1,3),2L,"replay"})));assertNull(failure.getCause());
            assertTrue(capture.list.stream().anyMatch(e->e.getFormattedMessage().contains("LABEL_ALREADY_USED")&&e.getFormattedMessage().contains(label)),()->capture.list.toString());
            assertTrue(capture.list.stream().noneMatch(e->e.getFormattedMessage().contains("replay")),"Payload never reaches the log");
        }finally{release(capture);}
        assertEquals(List.of("Success","Label Already Exists"),statuses);assertEquals(1,count("labels"));assertEquals("first",single("SELECT note FROM "+table("labels")));
        assertEquals("VISIBLE",transaction(label),"The first application is visible exactly once");
    }
    /** Commit and visibility happened on the server, but the receipt was lost: UNKNOWN, no JDBC rewrite, no resend; the label reconciles it out of band. */
    @Test void lostReceiptAfterVisibleCommitStaysUnknownAndTheLabelReconcilesIt()throws Exception {
        sql("CREATE TABLE "+table("lost")+RANGE);var fields=List.of("k","id","note");String label="dbswitch_probe_"+UUID.randomUUID().toString().replace("-","");
        var writer=writer("lost",fields,true,()->label);var capture=capture();
        try{assertThrows(UnconfirmedWriteException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{LocalDate.of(2026,1,2),1L,"applied"})));
            assertTrue(capture.list.stream().anyMatch(e->e.getFormattedMessage().contains("NO_RECEIPT")&&e.getFormattedMessage().contains(label)),()->capture.list.toString());}
        finally{release(capture);}
        assertEquals(1,requests,"Exactly one data request reached the backend");assertEquals(1,count("lost"));assertEquals("VISIBLE",transaction(label));
    }

    private DorisWriterImpl writer(String table,List<String> fields,boolean loseReceipt,Supplier<String> labels) {
        var writer=new DorisWriterImpl(pool);writer.prepareWrite(schema,table,fields);
        var helper=(OlapStreamLoadHelper)ReflectionTestUtils.getField(writer,"stream");assertNotNull(helper,"JDBC fallback cannot certify Stream Load");
        Function<RequestConfig,CloseableHttpClient> factory=config->mappedClient(config,loseReceipt);ReflectionTestUtils.setField(helper,"clientFactory",factory);
        if(labels!=null)ReflectionTestUtils.setField(helper,"labelFactory",labels);
        return writer;
    }
    private CloseableHttpClient mappedClient(RequestConfig config,boolean loseReceipt) {
        var socketFactory=new PlainConnectionSocketFactory(){@Override public Socket connectSocket(int timeout,Socket socket,HttpHost host,InetSocketAddress remote,InetSocketAddress local,HttpContext context)throws IOException{
            InetSocketAddress mapped=routes.get(host.getHostName()+":"+host.getPort());if(mapped==null)throw new IOException("Unknown fixture route");
            return super.connectSocket(timeout,socket,host,mapped,local,context);}};
        var registry=RegistryBuilder.<ConnectionSocketFactory>create().register("http",socketFactory).build();
        var manager=new BasicHttpClientConnectionManager(registry,null,null,host->new InetAddress[]{InetAddress.getLoopbackAddress()});
        HttpResponseInterceptor interceptor=(response,context)->{
            if(response.getStatusLine().getStatusCode()!=200)return;
            byte[] bytes=EntityUtils.toByteArray(response.getEntity());statuses.add(new ObjectMapper().readTree(bytes).path("Status").asText());requests++;
            if(loseReceipt)throw new IOException("controlled response loss after the real backend receipt");
            response.setEntity(new org.apache.http.entity.ByteArrayEntity(bytes));
        };
        return HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config).disableAutomaticRetries().disableRedirectHandling().disableContentCompression().addInterceptorLast(interceptor).build();
    }
    private static ListAppender<ILoggingEvent> capture(){var logger=(Logger)LoggerFactory.getLogger(OlapStreamLoadHelper.class);var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);return capture;}
    private static void release(ListAppender<ILoggingEvent> capture){((Logger)LoggerFactory.getLogger(OlapStreamLoadHelper.class)).detachAppender(capture);capture.stop();}
    private String transaction(String label)throws Exception{try(var s=control.createStatement();var r=s.executeQuery("SHOW TRANSACTION FROM `"+schema+"` WHERE LABEL = '"+label+"'")){assertTrue(r.next());String status=r.getString("TransactionStatus");assertFalse(r.next());return status;}}
    private Map<String,Long> partitions(String name)throws Exception{var names=new ArrayList<String>();try(var s=control.createStatement();var r=s.executeQuery("SHOW PARTITIONS FROM "+table(name))){while(r.next())names.add(r.getString("PartitionName"));}
        var counts=new TreeMap<String,Long>();for(String partition:names)counts.put(partition,Long.parseLong(single("SELECT COUNT(*) FROM "+table(name)+" PARTITION ("+partition+")")));return counts;}
    private long count(String name)throws Exception{return Long.parseLong(single("SELECT COUNT(*) FROM "+table(name)));}
    private String single(String query)throws Exception{try(var s=control.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());String value=r.getString(1);assertFalse(r.next());return value;}}
    private String table(String name){return "`"+schema+"`.`"+name+"`";}
    private void sql(String sql)throws Exception{try(var s=control.createStatement()){s.setQueryTimeout(30);s.execute(sql);}}
}
