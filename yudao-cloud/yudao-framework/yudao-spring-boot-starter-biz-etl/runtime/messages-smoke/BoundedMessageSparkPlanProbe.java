package cn.iocoder.yudao.framework.etl.messages;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.spark.EtlBoundedMessageProvider;
import org.apache.spark.sql.connector.catalog.SupportsRead;
import org.apache.spark.sql.connector.catalog.SupportsWrite;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.connector.write.RequiresDistributionAndOrdering;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 使用目标Spark全部API规划真实V2表，不启动SparkContext或访问外部消息服务。 */
public final class BoundedMessageSparkPlanProbe {
    public static void main(String[] arguments) {
        var schema = DataTypes.createStructType(List.of(DataTypes.createStructField("id", DataTypes.LongType, true), DataTypes.createStructField("label", DataTypes.StringType, true)));
        var types = new JSONObject().set("id", new JSONObject().set("sqlType", "BIGINT")).set("label", new JSONObject().set("sqlType", "STRING"));
        String encoded = Base64.getEncoder().encodeToString(new JSONObject().set("resultTypes", types).toString().getBytes(StandardCharsets.UTF_8));
        var provider = new EtlBoundedMessageProvider();
        var source = provider.getTable(schema, new Transform[0], Map.of("datahub.kind", "kafka", "datahub.direction", "source", "datahub.plan", encoded));
        var batch = ((SupportsRead) source).newScanBuilder(new CaseInsensitiveStringMap(Map.of())).build().toBatch();
        if (batch.planInputPartitions().length != 1 || batch.createReaderFactory() == null) throw new IllegalStateException("消息源没有唯一有限读取分区");
        var sink = provider.getTable(schema, new Transform[0], Map.of("datahub.kind", "kafka", "datahub.direction", "sink", "datahub.plan", encoded));
        var write = ((SupportsWrite) sink).newWriteBuilder(null).build();
        if (!(write instanceof RequiresDistributionAndOrdering requirements) || requirements.requiredNumPartitions() != 1) throw new IllegalStateException("消息输出没有强制唯一完整分区");
        boolean denied = false; try { write.toBatch().createBatchWriterFactory(() -> 2); } catch (IllegalStateException expected) { denied = true; }
        if (!denied) throw new IllegalStateException("额外消息输出分区未拒绝");
        System.out.println("spark-bounded-message-plans=passed; sourcePartitions=1; sinkPartitions=1; networkRequests=0");
    }
}
