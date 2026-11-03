package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeCryptoCompilerTest {
    @Test void requiresBothTaskSecretAuthorizationAndRegisteredFunction() {
        var expressions = new LinkedHashMap<>(Map.of("source", "`source`"));
        var types = new LinkedHashMap<>(Map.of("source", "STRING"));
        var config = JSONUtil.createObj().set("encryptFields", List.of(Map.of("fieldName", "source", "algorithm", "SM4", "secretKeyRef", "key1")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeCryptoCompiler.apply(EtlNativeSqlCompiler.Engine.FLINK,
                false, config, expressions, types, Set.of(), Set.of("DH_CRYPTO_ENCRYPT")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeCryptoCompiler.apply(EtlNativeSqlCompiler.Engine.FLINK,
                false, config, expressions, types, Set.of("key1"), Set.of()));
        EtlNativeCryptoCompiler.apply(EtlNativeSqlCompiler.Engine.FLINK, false, config, expressions, types,
                Set.of("key1"), Set.of("DH_CRYPTO_ENCRYPT"));
        assertEquals("DH_CRYPTO_ENCRYPT('SM4', 'key1', CAST(`source` AS STRING))", expressions.get("source"));
    }

    @Test void compilesHashesAndUnicodeBase64ForBothEngines() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            for (String algorithm : List.of("MD5", "SHA1", "SHA256", "SHA512", "BASE64")) {
                var expressions = new LinkedHashMap<>(Map.of("source", "`source`"));
                var types = new LinkedHashMap<>(Map.of("source", "STRING"));
                var rule = Map.of("fieldName", "source", "outputFieldName", "encoded", "algorithm", algorithm, "keepOriginal", true);
                EtlNativeCryptoCompiler.apply(engine, false, JSONUtil.createObj().set("encryptFields", List.of(rule)), expressions, types);
                assertEquals(2, expressions.size()); assertEquals("STRING", types.get("encoded"));
                assertTrue(expressions.get("encoded").contains("`source`"));
                if ("BASE64".equals(algorithm)) assertTrue(expressions.get("encoded").contains(engine == EtlNativeSqlCompiler.Engine.SPARK ? "ENCODE(" : "TO_BASE64("));
            }
        }
    }

    @Test void preservesSequentialRenameAndDecryptSemantics() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var expressions = new LinkedHashMap<>(Map.of("source", "`source`"));
            var types = new LinkedHashMap<>(Map.of("source", "STRING"));
            EtlNativeCryptoCompiler.apply(engine, false, JSONUtil.createObj().set("encryptFields", List.of(
                    Map.of("fieldName", "source", "outputFieldName", "encoded", "algorithm", "BASE64"))), expressions, types);
            assertFalse(expressions.containsKey("source"));
            EtlNativeCryptoCompiler.apply(engine, true, JSONUtil.createObj().set("decryptFields", List.of(
                    Map.of("fieldName", "encoded", "outputFieldName", "decoded", "algorithm", "BASE64"))), expressions, types);
            assertEquals(1, expressions.size()); assertFalse(expressions.containsKey("encoded"));
            assertTrue(expressions.get("decoded").contains(engine == EtlNativeSqlCompiler.Engine.SPARK ? "UNBASE64(" : "FROM_BASE64("));
        }
    }

    @Test void rejectsSecretsMissingFieldsAndIrreversibleDecryptWithoutLeakingValues() {
        var expressions = new LinkedHashMap<>(Map.of("source", "`source`"));
        var types = new LinkedHashMap<>(Map.of("source", "STRING"));
        String secret = "test-only-secret-sentinel";
        var error = assertThrows(IllegalArgumentException.class, () -> EtlNativeCryptoCompiler.apply(EtlNativeSqlCompiler.Engine.FLINK,
                false, JSONUtil.createObj().set("encryptFields", List.of(Map.of("fieldName", "source", "algorithm", "AES256", "secretKey", secret))), expressions, types));
        assertFalse(error.getMessage().contains(secret));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeCryptoCompiler.apply(EtlNativeSqlCompiler.Engine.FLINK, true,
                JSONUtil.createObj().set("decryptFields", List.of(Map.of("fieldName", "source", "algorithm", "MD5"))), expressions, types));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeCryptoCompiler.apply(EtlNativeSqlCompiler.Engine.FLINK, false,
                JSONUtil.createObj().set("encryptFields", List.of(Map.of("fieldName", "missing", "algorithm", "BASE64"))), expressions, types));
    }
}
