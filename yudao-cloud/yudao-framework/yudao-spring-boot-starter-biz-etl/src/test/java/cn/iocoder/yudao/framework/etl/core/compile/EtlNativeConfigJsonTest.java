package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeConfigJsonTest {
    @Test void postgresObjectKeyReorderingCannotChangeEmbeddedHttpPlanButArrayOrderIsPreserved() {
        var left = JSONUtil.parseObj("{\"headerSecretRefs\":{},\"apiConfig\":{\"requestMethod\":\"GET\",\"requestUrl\":\"http://fixture/rows\",\"dataColumns\":[{\"fieldName\":\"z\",\"fieldType\":12},{\"fieldName\":\"a\",\"fieldType\":-5}]}}");
        var right = JSONUtil.parseObj("{\"apiConfig\":{\"dataColumns\":[{\"fieldType\":12,\"fieldName\":\"z\"},{\"fieldType\":-5,\"fieldName\":\"a\"}],\"requestUrl\":\"http://fixture/rows\",\"requestMethod\":\"GET\"},\"headerSecretRefs\":{}}");
        var binding = new EtlDataxConnectorCompiler.Binding(Set.of("rest-api"), null, null, List.of("http://fixture/rows"), null,
                EtlDataxConnectorCompiler.Authentication.NONE, Set.of("spark-http"));
        var a = EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "same", "rest-api", EtlNativeConfigJson.normalize(left), binding, Set.of("DH_READ_API"), Set.of());
        var b = EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "same", "rest-api", EtlNativeConfigJson.normalize(right), binding, Set.of("DH_READ_API"), Set.of());
        assertEquals(a.initializationSql(), b.initializationSql());
        assertEquals(List.of("z", "a"), List.copyOf(a.table().columns().keySet()));
        var raw = JSONUtil.createObj().set("query", "{\"z\":1,\"a\":-1}").set("columns", List.of("z", "a"));
        var normalized = EtlNativeConfigJson.normalize(raw);
        assertEquals(raw.getStr("query"), normalized.getStr("query"));
        assertEquals(List.of("z", "a"), normalized.getJSONArray("columns").toList(String.class));
    }
}
