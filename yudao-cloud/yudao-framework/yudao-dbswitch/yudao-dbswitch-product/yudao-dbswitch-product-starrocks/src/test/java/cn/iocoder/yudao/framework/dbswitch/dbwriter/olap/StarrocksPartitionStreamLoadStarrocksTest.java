package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksWriterImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real StarRocks 3.5.21 Stream Load into static partitions: routing before HTTP, visibility, receipt states and server-side label idempotency. */
@EnabledIfSystemProperty(named="datahub.test.starrocks.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@Timeout(90)
class StarrocksPartitionStreamLoadStarrocksTest {
    static final String RANGE="(k DATE NOT NULL,id BIGINT NOT NULL,note VARCHAR(100),payload VARBINARY) PRIMARY KEY(k,id) PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('2026-01-01'),PARTITION p2 VALUES LESS THAN ('2026-02-01'),PARTITION pmax VALUES LESS THAN (MAXVALUE)) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')";
    private HikariDataSource pool;private Connection control;private String schema;private int frontendHttpPort;
    private final List<String> statuses=new CopyOnWriteArrayList<>();
    private final AtomicInteger requests=new AtomicInteger();

    @BeforeEach void setUp()throws Exception {
        pool=new HikariDataSource();pool.setJdbcUrl(System.getProperty("datahub.test.starrocks.url"));pool.setUsername("root");pool.setPassword("");pool.setMaximumPoolSize(3);pool.setMinimumIdle(0);
        control=pool.getConnection();schema="sr_part_stream_"+UUID.randomUUID().toString().replace("-","");sql("CREATE DATABASE "+schema);
        try(var s=control.createStatement();var r=s.executeQuery("SHOW FRONTENDS")){assertTrue(r.next());assertTrue(r.getString("Version").startsWith("3.5.21"));frontendHttpPort=r.getInt("HttpPort");}
    }
    @AfterEach void close()throws Exception {try{if(control!=null){try{if(schema!=null)sql("DROP DATABASE "+schema+" FORCE");}finally{control.close();}}}finally{if(pool!=null)pool.close();}}

    @Test void visibleReceiptPlacesBoundaryRowsAndBinaryValuesInTheirPartitionsAndIsReadableImmediately()throws Exception {
        sql("CREATE TABLE "+table("data_rows")+RANGE);var fields=List.of("k","id","note","payload");var writer=writer("data_rows",fields,false,null);
        var rows=List.of(new Object[]{LocalDate.of(2025,12,31),1L,"p1",new byte[]{0,(byte)255}},new Object[]{LocalDate.of(2026,1,1),2L,"p2 lower bound",new byte[0]},new Object[]{LocalDate.of(2026,1,31),3L,"p2",null},new Object[]{LocalDate.of(2026,2,1),4L,"pmax lower bound",new byte[]{1}});
        assertEquals(4,writer.writeWithReceipt(fields,rows).acknowledgedRows());assertEquals(List.of("Success"),statuses);
        assertEquals(Map.of("p1",1L,"p2",2L,"pmax",1L),partitions("data_rows"),"A VISIBLE receipt is readable without waiting");
        try(var s=control.createStatement();var r=s.executeQuery("SELECT payload FROM "+table("data_rows")+" ORDER BY id")){for(var row:rows){assertTrue(r.next());assertArrayEquals((byte[])row[3],r.getBytes(1));}}
    }
    @Test void outOfPartitionAndNullListBatchesAreRoutedBeforeAnyHttpRequest()throws Exception {
        sql("CREATE TABLE "+table("bounded")+RANGE.replace(",PARTITION pmax VALUES LESS THAN (MAXVALUE)",""));var fields=List.of("k","id","note","payload");var writer=writer("bounded",fields,false,null);
        assertThrows(IllegalArgumentException.class,()->writer.write(fields,List.of(new Object[]{LocalDate.of(2026,1,31),1L,"inside",null},new Object[]{LocalDate.of(2026,2,1),2L,"outside",null})));
        assertEquals(0,requests.get(),"Routing precedes the first HTTP request");assertEquals(0,count("bounded"));
        sql("CREATE TABLE "+table("nulls")+"(k INT NULL,id BIGINT NOT NULL,note VARCHAR(20)) DUPLICATE KEY(k,id) PARTITION BY LIST(k) (PARTITION p1 VALUES IN ('1'),PARTITION pn VALUES IN (NULL)) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        var nullFields=List.of("k","id","note");var nulls=writer("nulls",nullFields,false,null);
        assertEquals(2,nulls.write(nullFields,Arrays.asList(new Object[]{null,1L,"null key"},new Object[]{1,2L,"one"})));assertEquals(Map.of("p1",1L,"pn",1L),partitions("nulls"));
        int sent=requests.get();assertThrows(IllegalArgumentException.class,()->nulls.write(nullFields,List.<Object[]>of(new Object[]{2,3L,"no partition"})));assertEquals(sent,requests.get());assertEquals(2,count("nulls"));
    }
    @Test void reusedLabelIsRejectedByTheServerAndNeverAcknowledgedOrAppliedTwice()throws Exception {
        sql("CREATE TABLE "+table("labels")+RANGE);var fields=List.of("k","id","note","payload");String label="dbswitch_probe_"+UUID.randomUUID().toString().replace("-","");
        var writer=writer("labels",fields,false,()->label);
        assertEquals(1,writer.write(fields,List.<Object[]>of(new Object[]{LocalDate.of(2026,1,2),1L,"first",null})));
        var capture=capture();try{
            var failure=assertThrows(UnconfirmedWriteException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{LocalDate.of(2026,1,3),2L,"replay",null})));assertNull(failure.getCause());
            assertTrue(capture.list.stream().anyMatch(e->e.getFormattedMessage().contains("LABEL_ALREADY_USED")&&e.getFormattedMessage().contains(label)),()->capture.list.toString());
            assertTrue(capture.list.stream().noneMatch(e->e.getFormattedMessage().contains("replay")),"Payload never reaches the log");
        }finally{release(capture);}
        assertEquals(List.of("Success","Label Already Exists"),statuses);assertEquals(1,count("labels"));assertEquals("first",single("SELECT note FROM "+table("labels")));
        assertEquals("VISIBLE",loadState(label));
    }
    @Test void lostReceiptAfterVisibleCommitStaysUnknownAndTheLabelReconcilesIt()throws Exception {
        sql("CREATE TABLE "+table("lost")+RANGE);var fields=List.of("k","id","note","payload");String label="dbswitch_probe_"+UUID.randomUUID().toString().replace("-","");
        var writer=writer("lost",fields,true,()->label);var capture=capture();
        try{assertThrows(UnconfirmedWriteException.class,()->writer.write(fields,List.<Object[]>of(new Object[]{LocalDate.of(2026,1,2),1L,"applied",null})));
            assertTrue(capture.list.stream().anyMatch(e->e.getFormattedMessage().contains("NO_RECEIPT")&&e.getFormattedMessage().contains(label)),()->capture.list.toString());}
        finally{release(capture);}
        assertEquals(List.of("Success"),statuses,"Exactly one data request was answered; nothing was resent");assertEquals(1,count("lost"));assertEquals("VISIBLE",loadState(label));
    }

    private StarrocksWriterImpl writer(String table,List<String> fields,boolean loseReceipt,Supplier<String> labels) {
        var writer=new StarrocksWriterImpl(pool);writer.prepareWrite(schema,table,fields);
        var helper=(OlapStreamLoadHelper)ReflectionTestUtils.getField(writer,"streamLoadHelper");assertNotNull(helper,"JDBC fallback cannot certify Stream Load");
        Function<RequestConfig,CloseableHttpClient> factory=config->client(config,loseReceipt);ReflectionTestUtils.setField(helper,"clientFactory",factory);
        if(labels!=null)ReflectionTestUtils.setField(helper,"labelFactory",labels);
        return writer;
    }
    private CloseableHttpClient client(RequestConfig config,boolean loseReceipt) {
        HttpRequestInterceptor counter=(request,context)->requests.incrementAndGet();
        HttpResponseInterceptor interceptor=(response,context)->{
            if(response.getStatusLine().getStatusCode()!=200)return;
            byte[] bytes=EntityUtils.toByteArray(response.getEntity());statuses.add(new ObjectMapper().readTree(bytes).path("Status").asText());
            if(loseReceipt)throw new IOException("controlled response loss after the real backend receipt");
            response.setEntity(new org.apache.http.entity.ByteArrayEntity(bytes));
        };
        return HttpClients.custom().setDefaultRequestConfig(config).disableAutomaticRetries().disableRedirectHandling().disableContentCompression().addInterceptorFirst(counter).addInterceptorLast(interceptor).build();
    }
    /** StarRocks label state API, read-only and out of band of the write path. */
    private String loadState(String label)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+frontendHttpPort+"/api/"+schema+"/get_load_state?label="+label))
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString("root:".getBytes(StandardCharsets.UTF_8))).GET().build();
        try(var client=HttpClient.newHttpClient()){var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response::body);
            var node=new ObjectMapper().readTree(response.body());return node.hasNonNull("state")?node.get("state").asText():node.path("data").asText();}
    }
    private static ListAppender<ILoggingEvent> capture(){var logger=(Logger)LoggerFactory.getLogger(OlapStreamLoadHelper.class);var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);return capture;}
    private static void release(ListAppender<ILoggingEvent> capture){((Logger)LoggerFactory.getLogger(OlapStreamLoadHelper.class)).detachAppender(capture);capture.stop();}
    private Map<String,Long> partitions(String name)throws Exception{var names=new ArrayList<String>();try(var s=control.createStatement();var r=s.executeQuery("SHOW PARTITIONS FROM "+table(name))){while(r.next())names.add(r.getString("PartitionName"));}
        var counts=new TreeMap<String,Long>();for(String partition:names)counts.put(partition,Long.parseLong(single("SELECT COUNT(*) FROM "+table(name)+" PARTITION ("+partition+")")));return counts;}
    private long count(String name)throws Exception{return Long.parseLong(single("SELECT COUNT(*) FROM "+table(name)));}
    private String single(String query)throws Exception{try(var s=control.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());String value=r.getString(1);assertFalse(r.next());return value;}}
    private String table(String name){return schema+"."+name;}
    private void sql(String sql)throws Exception{try(var s=control.createStatement()){s.setQueryTimeout(30);s.execute(sql);}}
}
