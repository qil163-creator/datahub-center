package cn.iocoder.yudao.framework.etl.spark;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedOdps;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde2.objectinspector.*;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 官方 ODPS Tunnel 原生 Spark 有界行源。 */
public final class EtlOdpsRowsFunction extends GenericUDTF {
    private transient StringObjectInspector inspector;
    @Override public StructObjectInspector initialize(ObjectInspector[] arguments) throws UDFArgumentException {
        if (arguments.length != 1 || !(arguments[0] instanceof StringObjectInspector)) throw new UDFArgumentException("ODPS 需要受管执行计划");
        inspector = (StringObjectInspector) arguments[0];
        return ObjectInspectorFactory.getStandardStructObjectInspector(List.of("dh_api_json"), List.of(PrimitiveObjectInspectorFactory.javaStringObjectInspector));
    }
    @Override public void process(Object[] arguments) throws HiveException {
        try {
            String encoded = inspector.getPrimitiveJavaObject(arguments[0]); if (encoded == null || encoded.length() > 1048576) throw new IllegalArgumentException();
            var plan = JSONUtil.parseObj(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            EtlManagedOdps.read(plan, EtlManagedSecrets::readFromEnvironment, row -> {
                try { forward(new Object[]{row}); } catch (HiveException failure) { throw new IllegalStateException("ODPS 行输出失败"); }
            });
        } catch (RuntimeException failure) { throw new HiveException("受管 ODPS Tunnel 读取失败，请核查真实表分区、字段、预算和凭据引用"); }
    }
    @Override public void close() { }
}
