package cn.iocoder.yudao.framework.etl.flink.jdbc;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.*;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.runtime.typeutils.ExternalTypeInfo;
import org.apache.flink.types.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 使用真实 PostgreSQL 与官方 Flink STREAMING 作业验证受管目录的增量、删除和空主键失败。 */
public final class VerifiedJdbcUpsertSmoke {
    public static void main(String[] args) throws Exception {
        String scenario=args[0],url=args[1];Path sql=Path.of(args[2]);
        var configuration=new Configuration();configuration.setString("taskmanager.memory.managed.size","32m");configuration.setString("taskmanager.memory.network.min","16m");configuration.setString("taskmanager.memory.network.max","16m");configuration.setString("taskmanager.numberOfTaskSlots","1");configuration.setString("rest.bind-port","0");configuration.setString("restart-strategy.type","none");
        var environment=StreamExecutionEnvironment.createLocalEnvironment(1,configuration);environment.setRuntimeMode(RuntimeExecutionMode.STREAMING);environment.enableCheckpointing(300);
        var tables=StreamTableEnvironment.create(environment);
        String pk=scenario.equals("missing-catalog-pk")?"":", PRIMARY KEY (id) NOT ENFORCED";
        tables.executeSql("CREATE TABLE original_sink (id BIGINT NOT NULL,label STRING"+pk+") WITH ('connector'='jdbc','url'='"+url+"','table-name'='public.target','username'='qa_upsert','password'='unused-fixture-trust-placeholder','sink.buffer-flush.interval'='100ms','sink.max-retries'='0')");
        String ddl=Files.readString(sql);
        if(scenario.equals("wrong-url"))ddl=ddl.replaceFirst("datahub.url-sha256'='[a-f0-9]{64}","datahub.url-sha256'='"+"0".repeat(64));
        String alias=ddl.substring(ddl.indexOf('`')+1,ddl.indexOf('`',ddl.indexOf('`')+1));
        tables.executeSql(ddl);
        List<Row> events;
        if(scenario.equals("null-key"))events=List.of(Row.of(null,"bad"));
        else if(scenario.equals("changelog"))events=List.of(Row.ofKind(RowKind.INSERT,9007199254740993L,"one"),Row.ofKind(RowKind.UPDATE_AFTER,9007199254740993L,"two😀"),Row.ofKind(RowKind.INSERT,9007199254740994L,"remove"),Row.ofKind(RowKind.DELETE,9007199254740994L,"remove"));
        else events=List.of(Row.of(9007199254740993L,"one"),Row.of(9007199254740993L,"two😀"),Row.of(9007199254740994L,"three"));
        var type=DataTypes.ROW(DataTypes.FIELD("id",DataTypes.BIGINT()),DataTypes.FIELD("label",DataTypes.STRING()));
        var rows=environment.fromCollection(events,ExternalTypeInfo.of(type)).map(row->{Thread.sleep(400);return row;},ExternalTypeInfo.of(type));
        var schema=Schema.newBuilder().fromRowDataType(type).build();
        tables.createTemporaryView("incoming",scenario.equals("changelog")?tables.fromChangelogStream(rows,schema):tables.fromDataStream(rows,schema));
        try { tables.executeSql("INSERT INTO `"+alias+"` SELECT id,label FROM incoming").await(); }
        catch(Exception failure) {
            String expected=switch(scenario){case "missing-catalog-pk"->"原 Catalog 未声明";case "wrong-url"->"实际服务或物理表";case "null-key"->"null";default->null;};
            if(expected==null||!hasReason(failure,expected))throw failure;
            if(count(url)!=0)throw new IllegalStateException("负例产生了数据写入");
            System.out.println("VERIFIED_JDBC_REJECT "+scenario);return;
        }
        if(!scenario.equals("upsert")&&!scenario.equals("changelog"))throw new IllegalStateException("负例未被拒绝");
        try(var connection=DriverManager.getConnection(url,"qa_upsert","unused-fixture-trust-placeholder");var statement=connection.createStatement();var result=statement.executeQuery("SELECT id,label FROM public.target ORDER BY id")){
            var actual=new ArrayList<String>();while(result.next())actual.add(result.getLong(1)+":"+result.getString(2));
            int expected=scenario.equals("changelog")?1:2;
            if(actual.size()!=expected||!actual.get(0).equals("9007199254740993:two😀"))throw new IllegalStateException("实际主键更新或删除结果不符："+actual);
            System.out.println("VERIFIED_JDBC_PASS "+scenario+" rows="+actual.size());
        }
    }
    private static long count(String url)throws Exception {try(var connection=DriverManager.getConnection(url,"qa_upsert","unused-fixture-trust-placeholder");var statement=connection.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM public.target")){result.next();return result.getLong(1);}}
    private static boolean hasReason(Throwable failure,String expected){for(Throwable current=failure;current!=null;current=current.getCause())if(String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)))return true;return false;}
}
