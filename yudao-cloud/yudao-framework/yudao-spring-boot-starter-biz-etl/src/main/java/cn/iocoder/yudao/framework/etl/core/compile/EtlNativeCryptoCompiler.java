package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将无密钥的字段摘要和 Base64 转换编译为原生函数，密钥算法必须使用独立受管运行绑定。 */
public final class EtlNativeCryptoCompiler {
    private EtlNativeCryptoCompiler() { }

    /** 按节点字段顺序更新投影，保留已有执行器的覆盖和保留源字段语义。 */
    public static void apply(EtlNativeSqlCompiler.Engine engine, boolean decrypt, JSONObject config,
                             Map<String, String> expressions, Map<String, String> types) {
        apply(engine, decrypt, config, expressions, types, Set.of(), Set.of());
    }

    public static void apply(EtlNativeSqlCompiler.Engine engine, boolean decrypt, JSONObject config,
                             Map<String, String> expressions, Map<String, String> types,
                             Set<String> allowedSecretRefs, Set<String> managedFunctions) {
        var fields = config.getJSONArray(decrypt ? "decryptFields" : "encryptFields");
        if (fields == null || fields.isEmpty() || fields.size() > 5000)
            throw invalid("请配置1至5000个加解密字段");
        for (Object item : fields) {
            var rule = JSONUtil.parseObj(item);
            String source = rule.getStr("fieldName", "");
            if (!expressions.containsKey(source)) throw invalid("加解密来源字段不存在");
            if (!rule.getStr("secretKey", "").isBlank()) throw invalid("加解密节点禁止内联密钥，请使用受管密钥绑定");
            String algorithm = rule.getStr("algorithm", "AES256").toUpperCase(Locale.ROOT);
            String expression;
            if (Set.of("AES", "SM4", "AES128", "AES256", "DES", "3DES").contains(algorithm)) {
                String reference = rule.getStr("secretKeyRef", "");
                if (!reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !allowedSecretRefs.contains(reference))
                    throw invalid("当前任务未获授权使用此密钥引用");
                String function = decrypt ? "DH_CRYPTO_DECRYPT" : "DH_CRYPTO_ENCRYPT";
                if (!managedFunctions.contains(function)) throw invalid("当前引擎尚未注册受管加解密函数");
                expression = function + "('" + algorithm + "', '" + reference + "', CAST(" + expressions.get(source) + " AS STRING))";
            } else {
                if (!rule.getStr("secretKeyRef", "").isBlank()) throw invalid("摘要或 Base64 转换不应配置密钥引用");
                expression = expression(engine, decrypt, algorithm, expressions.get(source));
            }
            String target = rule.getStr("outputFieldName", "");
            if (target.isBlank()) target = source;
            if (target.length() > 200 || target.indexOf('\0') >= 0) throw invalid("加解密输出字段无效");
            if (!rule.getBool("keepOriginal", false) && !target.equals(source)) {
                expressions.remove(source); types.remove(source);
            }
            expressions.put(target, expression); types.put(target, "STRING");
        }
    }

    private static String expression(EtlNativeSqlCompiler.Engine engine, boolean decrypt, String algorithm, String input) {
        String value = "CAST(" + input + " AS STRING)";
        if (decrypt) {
            if (!"BASE64".equals(algorithm))
                throw invalid(Set.of("MD5", "SHA1", "SHA256", "SHA512").contains(algorithm)
                        ? "摘要算法不可逆，不能用于解密" : "此密钥算法尚未绑定受管执行函数");
            return engine == EtlNativeSqlCompiler.Engine.SPARK ? "DECODE(UNBASE64(" + value + "), 'UTF-8')"
                    : "FROM_BASE64(" + value + ")";
        }
        return switch (algorithm) {
            case "BASE64" -> engine == EtlNativeSqlCompiler.Engine.SPARK ? "BASE64(ENCODE(" + value + ", 'UTF-8'))"
                    : "TO_BASE64(" + value + ")";
            case "MD5", "SHA1" -> algorithm + "(" + value + ")";
            case "SHA256" -> "SHA2(" + value + ", 256)";
            case "SHA512" -> "SHA2(" + value + ", 512)";
            default -> throw invalid("此密钥算法尚未绑定受管执行函数");
        };
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
