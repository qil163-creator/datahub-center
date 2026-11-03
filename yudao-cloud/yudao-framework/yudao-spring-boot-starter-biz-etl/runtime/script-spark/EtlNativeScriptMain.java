package cn.iocoder.yudao.framework.etl.script.spark;

import cn.iocoder.yudao.framework.etl.core.script.*;
import org.apache.spark.sql.*;
import org.apache.spark.sql.types.*;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.TaskContext;
import java.math.*;
import java.time.*;
import java.util.*;

/** 受管 Spark stage 只处理类型与 RPC；用户源码始终在 network=none Docker worker。 */
public final class EtlNativeScriptMain {
    private EtlNativeScriptMain() { }
    public static void main(String[] arguments) {
        var program=EtlNativeScriptProgram.fromArguments(arguments);
        if(!"SPARK".equals(program.engine())||program.streaming())throw invalid();
        String run=UUID.randomUUID().toString(); var cached=new ArrayList<Dataset<Row>>();
        try(var spark=SparkSession.builder().appName("datahub-managed-script").enableHiveSupport().getOrCreate()) {
            spark.conf().set("spark.sql.parser.escapedStringLiterals","true");
            spark.conf().set("spark.sql.shuffle.partitions",program.parallelism());
            var contexts=new LinkedHashMap<String,String>(); EtlScriptBrokerClient.Binding identity=null;
            for(var initializer:program.initializers()) {
                try(var client=new EtlScriptBrokerClient(initializer.binding(),run,"initializer-"+initializer.id(),null)) {
                    var result=client.execute(0,List.of());if(!result.rows().isEmpty()||result.authenticationContext().isEmpty())throw invalid();
                    contexts.put(initializer.tokenKey(),result.authenticationContext()); if(identity==null)identity=initializer.binding();
                }
            }
            if(identity!=null)spark.sparkContext().setLocalProperty(EtlScriptAuthenticatedHttp.RUNTIME_CONTEXT_KEY,EtlScriptAuthenticatedHttp.encode(new EtlScriptAuthenticatedHttp.Context(identity,run,contexts)));
            for(String sql:program.initialization())spark.sql(sql).collectAsList();
            for(var stage:program.stages()) {
                StructType input=schema(stage.inputFields()),output=schema(stage.outputFields());
                Dataset<Row> source=spark.sql(stage.inputSql());
                if(!source.schema().sameType(input))throw invalid();
                Dataset<Row> rows=source.mapPartitions(new Partition(stage,run),Encoders.row(output)).persist(StorageLevel.MEMORY_AND_DISK());
                cached.add(rows);rows.count();rows.createOrReplaceTempView(stage.view());
            }
            for(String sql:program.inserts())spark.sql(sql).collectAsList();
            System.out.println("DATAHUB_NATIVE_SCRIPT_COMPLETED");
        }finally{for(var rows:cached)try{rows.unpersist(false);}catch(RuntimeException ignored){}}
    }
    private record Partition(EtlNativeScriptProgram.Stage stage,String run) implements MapPartitionsFunction<Row,Row> {
        @Override public Iterator<Row> call(Iterator<Row> rows) {
            if(!stage.partitionBatch())throw invalid();
            var batch=new ArrayList<Map<String,Object>>();
            while(rows.hasNext()) {
                if(batch.size()>=stage.maximumRows())throw new IllegalArgumentException("完整分区超过声明脚本批次预算；不会暗中拆分整批语义");
                Row row=rows.next();var values=new LinkedHashMap<String,Object>();
                for(int index=0;index<stage.inputFields().size();index++)values.put(stage.inputFields().get(index).name(),input(row.get(index)));
                batch.add(values);
            }
            var task=TaskContext.get();String partition=stage.id()+"-"+task.partitionId();
            var client=new EtlScriptBrokerClient(stage.binding(),run,partition,null);boolean successful=false;
            try {
                var result=client.execute(0,batch);if(!result.authenticationContext().isEmpty())throw invalid();
                var output=new ArrayList<Row>();
                for(var row:result.rows()) {
                    if(row.size()!=stage.outputFields().size())throw invalid();var values=new Object[stage.outputFields().size()];
                    for(int index=0;index<values.length;index++){var field=stage.outputFields().get(index);if(!row.containsKey(field.name()))throw invalid();values[index]=output(row.get(field.name()),field);}
                    output.add(RowFactory.create(values));
                }
                client.close();successful=true;return output.iterator();
            }finally{if(!successful)client.cancel();}
        }
    }
    private static Object input(Object value){if(value instanceof java.sql.Date day)return day.toLocalDate();if(value instanceof java.sql.Timestamp timestamp)return timestamp.toLocalDateTime();return value;}
    private static Object output(Object value,EtlNativeScriptProgram.Field field){
        if(value==null){if(Boolean.FALSE.equals(field.nullable()))throw invalid();return null;}
        String type=field.sqlType();
        if(type.equals("STRING")){if(!(value instanceof String))throw invalid();return value;}
        if(type.equals("BOOLEAN")){if(!(value instanceof Boolean))throw invalid();return value;}
        if(Set.of("TINYINT","SMALLINT","INT","BIGINT").contains(type)){
            BigDecimal number=exact(value);try{return switch(type){case "TINYINT"->number.byteValueExact();case "SMALLINT"->number.shortValueExact();case "INT"->number.intValueExact();default->number.longValueExact();};}catch(ArithmeticException failure){throw invalid();}
        }
        if(type.equals("DOUBLE")||type.equals("FLOAT")){if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||type.equals("FLOAT")&&!Float.isFinite(number.floatValue()))throw invalid();if(type.equals("DOUBLE"))return number.doubleValue();return number.floatValue();}
        if(type.startsWith("DECIMAL(")){var shape=type.substring(8,type.length()-1).split(",");BigDecimal number=exact(value);try{number=number.setScale(Integer.parseInt(shape[1]),RoundingMode.UNNECESSARY);}catch(ArithmeticException failure){throw invalid();}if(number.precision()>Integer.parseInt(shape[0]))throw invalid();return number;}
        if(type.equals("DATE")){if(value instanceof LocalDate day)return java.sql.Date.valueOf(day);if(value instanceof java.sql.Date)return value;throw invalid();}
        if(type.startsWith("TIMESTAMP(")){LocalDateTime timestamp=value instanceof LocalDateTime local?local:value instanceof java.sql.Timestamp sql?sql.toLocalDateTime():null;if(timestamp==null)throw invalid();int precision=Integer.parseInt(type.substring(10,11));if(timestamp.getNano()%(int)Math.pow(10,9-precision)!=0)throw invalid();return java.sql.Timestamp.valueOf(timestamp);}
        throw invalid();
    }
    private static BigDecimal exact(Object value){if(value instanceof BigDecimal decimal)return decimal;if(value instanceof BigInteger integer)return new BigDecimal(integer);if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)return BigDecimal.valueOf(((Number)value).longValue());throw invalid();}
    private static StructType schema(List<EtlNativeScriptProgram.Field> fields){return new StructType(fields.stream().map(field->new StructField(field.name(),DataType.fromDDL(field.sqlType()),!Boolean.FALSE.equals(field.nullable()),Metadata.empty())).toArray(StructField[]::new));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("受管Spark脚本程序、输入输出字段或类型不符合已发布合同");}
}
