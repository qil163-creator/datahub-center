package cn.iocoder.yudao.framework.etl.spark;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlWebServiceRows;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlNativeRowGuard;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** WebService行源仅允许工作节点环境中的精确地址白名单，函数本身不注册或修改授权。 */
public final class EtlWebServiceRowsFunction extends GenericUDTF {
    private transient StringObjectInspector input;

    @Override public StructObjectInspector initialize(ObjectInspector[] arguments) throws UDFArgumentException {
        if (arguments.length != 1 || !(arguments[0] instanceof StringObjectInspector)) throw new UDFArgumentException("WebService行源需要一个受管字符串执行计划");
        input = (StringObjectInspector) arguments[0];
        return ObjectInspectorFactory.getStandardStructObjectInspector(List.of("dh_api_json"), List.of(PrimitiveObjectInspectorFactory.javaStringObjectInspector));
    }

    @Override public void process(Object[] arguments) throws HiveException {
        try {
            String encoded = input.getPrimitiveJavaObject(arguments[0]);
            if (encoded == null || encoded.length() > 1048576) throw new IllegalArgumentException("WebService行源计划无效或超限");
            var plan = JSONUtil.parseObj(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            String environment = System.getenv("DATAHUB_ETL_HTTP_ALLOWLIST");
            Set<String> allowed = environment == null ? Set.of() : Set.copyOf(JSONUtil.parseArray(environment).toList(String.class));
            EtlWebServiceRows.read(plan, allowed, EtlManagedSecrets::readFromEnvironment, row -> {
                try { forward(new Object[]{EtlNativeRowGuard.validate(row, plan)}); } catch (HiveException failure) { throw new IllegalStateException("WebService行输出失败"); }
            });
        } catch (RuntimeException failure) { throw new HiveException("受管WebService读取失败，请检查授权、WSDL绑定、响应形状和读取预算"); }
    }
    @Override public void close() { }
}
