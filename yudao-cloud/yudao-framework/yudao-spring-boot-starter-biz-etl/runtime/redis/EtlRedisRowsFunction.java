package cn.iocoder.yudao.framework.etl.spark;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedRedis;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde2.objectinspector.*;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 有界 Redis 行源，实际读取仍在原生 Spark 执行工作节点中完成。 */
public final class EtlRedisRowsFunction extends GenericUDTF {
    private transient StringObjectInspector inspector;
    @Override public StructObjectInspector initialize(ObjectInspector[] arguments) throws UDFArgumentException {
        if (arguments.length != 1 || !(arguments[0] instanceof StringObjectInspector)) throw new UDFArgumentException("Redis 行源需要一个受管执行计划");
        inspector = (StringObjectInspector) arguments[0];
        return ObjectInspectorFactory.getStandardStructObjectInspector(List.of("dh_api_json"), List.of(PrimitiveObjectInspectorFactory.javaStringObjectInspector));
    }
    @Override public void process(Object[] arguments) throws HiveException {
        try {
            String encoded = inspector.getPrimitiveJavaObject(arguments[0]);
            if (encoded == null || encoded.length() > 1048576) throw new IllegalArgumentException();
            var plan = JSONUtil.parseObj(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            EtlManagedRedis.read(plan, EtlManagedSecrets::readFromEnvironment, row -> {
                try { forward(new Object[]{row}); } catch (HiveException failure) { throw new IllegalStateException("Redis 行输出失败"); }
            });
        } catch (RuntimeException failure) { throw new HiveException("受管 Redis 读取失败，请检查类型、认证、Key 范围与预算"); }
    }
    @Override public void close() { }
}
