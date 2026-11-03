package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Reproducible writer baseline, not an old-version comparison or a wall-clock performance gate. */
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class WriterPerformancePostgreSqlTest {
    private static final int ROWS=4096, BATCH_ROWS=256, ROUNDS=3;
    private static final List<String> FIELDS=List.of("id","amount","payload","binary_value");
    private static final BatchLimits LIMITS=new BatchLimits(BATCH_ROWS,1_048_576,8192,65535,1000);

    @Test void fixedSampleComparesJdbcBatchComponentInsertAndCopyAndArchivesResourceFacts() throws Exception {
        List<Object[]> sample=sample();long logicalBytes=logicalBytes(sample);
        String schema="writer_benchmark_"+UUID.randomUUID().toString().replace("-","");
        var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        properties.setUrl(System.getProperty("datahub.test.postgres.url"));
        properties.setUsername(System.getProperty("datahub.test.postgres.user","dataforge_test"));
        properties.setPassword(System.getProperty("datahub.test.postgres.password",""));properties.setPoolSize(2);
        List<String> report=new ArrayList<>();
        report.add("round,mode,rows,logical_utf8_plus_binary_bytes,batches,elapsed_nanos,rows_per_second,logical_bytes_per_second,estimated_input_batch_peak_bytes,borrowed_connection_peak,heap_before_bytes,heap_after_bytes,gc_count_delta,gc_time_ms_delta,postgres_version,driver_version,jvm_version");
        try(var runtime=DbSwitchRuntime.standalone();var pool=DataSourceUtils.createTargetDataSource(properties,null,null,null,runtime.drivers().runtimes(),runtime.options())){
            var measured=new BorrowedConnections(pool);
            try(var control=pool.getConnection()){
                assertEquals(16,control.getMetaData().getDatabaseMajorVersion());
                sql(control,"CREATE SCHEMA "+schema);
                try{
                    sql(control,"CREATE TABLE "+schema+".rows(id bigint PRIMARY KEY,amount numeric(38,8),payload text,binary_value bytea)");
                    for(String mode:List.of("JDBC_BATCH","COMPONENT_INSERT","COMPONENT_COPY")){
                        // Warm-up and correctness checks are outside each measured write interval.
                        sql(control,"TRUNCATE "+schema+".rows");
                        write(runtime,measured,schema,mode,sample.subList(0,BATCH_ROWS));
                    }
                    for(int round=1;round<=ROUNDS;round++)for(String mode:List.of("JDBC_BATCH","COMPONENT_INSERT","COMPONENT_COPY")){
                        sql(control,"TRUNCATE "+schema+".rows");measured.reset();
                        var before=resources();long started=System.nanoTime();
                        Measurement result=write(runtime,measured,schema,mode,sample);
                        long elapsed=System.nanoTime()-started;var after=resources();
                        verify(control,schema,sample);
                        assertEquals(0,measured.borrowed.get(),"all borrowed writer connections must return");
                        assertEquals(ROWS,result.rows);assertEquals(16,result.batches);assertTrue(result.peakBytes<=LIMITS.maxBatchBytes());
                        assertTrue(elapsed>0); // No latency/throughput threshold is asserted.
                        report.add(String.join(",",Integer.toString(round),mode,Integer.toString(ROWS),Long.toString(logicalBytes),
                                Long.toString(result.batches),Long.toString(elapsed),decimal(ROWS*1e9/elapsed),decimal(logicalBytes*1e9/elapsed),
                                Long.toString(result.peakBytes),Integer.toString(measured.peak.get()),Long.toString(before.heap),Long.toString(after.heap),
                                Long.toString(after.gcCount-before.gcCount),Long.toString(after.gcMillis-before.gcMillis),
                                quoted(control.getMetaData().getDatabaseProductVersion()),quoted(control.getMetaData().getDriverVersion()),quoted(System.getProperty("java.version"))));
                    }
                }finally{sql(control,"DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
            }
        }
        Path directory=Path.of("target","dbswitch-performance");Files.createDirectories(directory);
        Files.write(directory.resolve("writer-pg16-baseline.csv"),report,StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("README.txt"),"""
                固定样本：4096 行；每批 256 行；三轮，每轮按 JDBC_BATCH、COMPONENT_INSERT、COMPONENT_COPY 顺序执行。
                同一正式 DB Switch Hikari 池，池上限 2；各模式均每批提交，组件模式包含各自实际事务边界及已实现的提交验证开销。
                样本包括精确 numeric(38,8)、NULL/中文 text 与 bytea；测量外逐行核验全部值，并检查写连接归还。
                logical_utf8_plus_binary_bytes 是标量文本 UTF-8 与二进制的样本字节之和，不是网络流量或堆分配。
                estimated_input_batch_peak_bytes 是转换器对一次输入批次的保守估算，不是 JVM 峰值或COPY内部缓冲。
                heap_before/after 与GC是观测快照，不能据此宣称无泄漏；固定常驻样本占用所有模式共同持有。
                elapsed 包含组件 writer 准备与提交验证，不包含预热、清表、完整值核验；不设置时延阈值。
                这是当前实现的可重复基线，不是与旧版本的回归比较，也不构成对其他产品/驱动的认证。
                """,StandardCharsets.UTF_8);
        assertEquals(ROWS,sample.size(),"writers must not clear caller-owned input");
    }

    private Measurement write(DbSwitchRuntime runtime,DataSource source,String schema,String mode,List<Object[]> rows) throws Exception {
        IDatabaseWriter writer=null;
        if(!mode.equals("JDBC_BATCH")){
            writer=runtime.writer(source,ProductTypeEnum.POSTGRESQL,mode.equals("COMPONENT_COPY")?WriteMode.COPY:WriteMode.INSERT);
            writer.configureBatchLimits(LIMITS);writer.prepareWrite(schema,"rows",FIELDS);
        }
        long count=0,batches=0,peak=0;
        for(int offset=0;offset<rows.size();offset+=BATCH_ROWS){
            var batch=rows.subList(offset,Math.min(offset+BATCH_ROWS,rows.size()));
            long bytes=batch.stream().mapToLong(BatchValueConverter::estimatedRowBytes).sum();peak=Math.max(peak,bytes);
            if(writer==null){
                try(var connection=source.getConnection()){
                    connection.setAutoCommit(false);
                    try(var statement=connection.prepareStatement("INSERT INTO "+schema+".rows VALUES(?,?,?,?)")){
                        for(Object[] row:batch){statement.setLong(1,(Long)row[0]);statement.setBigDecimal(2,(BigDecimal)row[1]);
                            statement.setString(3,(String)row[2]);statement.setBytes(4,(byte[])row[3]);statement.addBatch();}
                        assertEquals(batch.size(),statement.executeBatch().length);connection.commit();
                    }catch(Throwable failure){connection.rollback();throw failure;}
                    finally{connection.setAutoCommit(true);}
                }
                count+=batch.size();
            }else{
                var written=writer.writeWithReceipt(FIELDS,batch);assertEquals(batch.size(),written.acknowledgedRows());count+=written.acknowledgedRows();
            }
            batches++;
        }
        return new Measurement(count,batches,peak);
    }
    private static List<Object[]> sample(){
        var rows=new ArrayList<Object[]>(ROWS);String text="p".repeat(896)+"中文".repeat(32);
        var amount=new BigDecimal("123456789012345678901234567890.12345678");
        for(int i=0;i<ROWS;i++){byte[] bytes=new byte[128];Arrays.fill(bytes,(byte)(i%251));
            rows.add(new Object[]{(long)i,amount.add(BigDecimal.valueOf(i).movePointLeft(8)),i%17==0?null:text,bytes});}
        return List.copyOf(rows);
    }
    private static long logicalBytes(List<Object[]> rows){long total=0;for(Object[] row:rows)for(Object value:row){
        if(value instanceof byte[] bytes)total+=bytes.length;else if(value!=null)total+=value.toString().getBytes(StandardCharsets.UTF_8).length;}
        return total;}
    private static void verify(Connection connection,String schema,List<Object[]> expected)throws Exception{
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,amount,payload,binary_value FROM "+schema+".rows ORDER BY id")){
            for(Object[] row:expected){assertTrue(rows.next());assertEquals(row[0],rows.getLong(1));assertEquals(row[1],rows.getBigDecimal(2));
                assertEquals(row[2],rows.getString(3));assertArrayEquals((byte[])row[3],rows.getBytes(4));}assertFalse(rows.next());
        }
    }
    private static void sql(Connection connection,String sql)throws Exception{try(var statement=connection.createStatement()){statement.execute(sql);}}
    private static Resources resources(){long count=0,millis=0;for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){
        if(gc.getCollectionCount()>0)count+=gc.getCollectionCount();if(gc.getCollectionTime()>0)millis+=gc.getCollectionTime();}
        return new Resources(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),count,millis);}
    private static String decimal(double value){return String.format(Locale.ROOT,"%.3f",value);}
    private static String quoted(String value){return '"'+value.replace("\"","\"\"")+'"';}
    private record Measurement(long rows,long batches,long peakBytes){}
    private record Resources(long heap,long gcCount,long gcMillis){}
    private static final class BorrowedConnections extends AbstractDataSource{
        private final DataSource delegate;private final AtomicInteger borrowed=new AtomicInteger(),peak=new AtomicInteger();
        private BorrowedConnections(DataSource delegate){this.delegate=delegate;}
        void reset(){assertEquals(0,borrowed.get());peak.set(0);}
        @Override public Connection getConnection()throws SQLException{return tracked(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return tracked(delegate.getConnection(user,password));}
        private Connection tracked(Connection connection){int current=borrowed.incrementAndGet();peak.accumulateAndGet(current,Math::max);var closed=new AtomicBoolean();
            return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{Object value=method.invoke(connection,args);if(method.getName().equals("close")&&closed.compareAndSet(false,true))borrowed.decrementAndGet();return value;}
                catch(InvocationTargetException failure){throw failure.getCause();}
            });}
    }
}
