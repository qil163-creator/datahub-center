package cn.iocoder.yudao.framework.seatunnel;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;

/** 原生作业的传输校验，不改写配置，也不限制连接器、转换或表结构。 */
public final class NativeSeatunnelConfig {

    public static final int MAX_CONFIG_LENGTH = 1_048_576;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * 初始化 {@code NativeSeatunnelConfig}。
     */
    private NativeSeatunnelConfig() {
    }

    /** 未指定格式时兼容原有 JSON 提交方式。 */
    public static String normalizeFormat(String format) {
        String normalized = format == null || format.isBlank() ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if (!"json".equals(normalized) && !"hocon".equals(normalized)) {
            throw new IllegalArgumentException("SeaTunnel 配置格式仅支持 json 或 hocon");
        }
        return normalized;
    }

    /**
     * JSON 仅检查对象语法；HOCON 的语法、include 和变量替换由 SeaTunnel 原生解析。
     * 本地不读取配置引用的资源或环境变量，校验异常不回显可能含凭据的原文。
     */
    public static void validate(String source, String format) {
        String normalized = normalizeFormat(format);
        if (source == null || source.isBlank() || source.length() > MAX_CONFIG_LENGTH) {
            throw new IllegalArgumentException("SeaTunnel 作业配置不能为空或超过 1048576 个字符");
        }
        if ("hocon".equals(normalized)) {
            return;
        }
        try {
            if (!JSON.readTree(source).isObject()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("SeaTunnel JSON 配置必须是合法的 JSON 对象");
        }
    }
}
