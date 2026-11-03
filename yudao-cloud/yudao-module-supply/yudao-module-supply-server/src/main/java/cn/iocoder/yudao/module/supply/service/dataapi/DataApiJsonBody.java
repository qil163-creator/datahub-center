package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_REQUEST_INVALID;

/**
 * v2 JSON 请求体：按原始字符串解析，拒绝重复属性，不重新序列化后再哈希。
 */
public final class DataApiJsonBody {

    private static final JsonMapper STRICT_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /**
     * 限制数据 APIJSON请求体工具的实例化，统一通过静态能力使用。
     */
    private DataApiJsonBody() {
    }

    /**
     * 解析对象。
     *
     * @param rawBody 原始请求体
     * @return 处理后的键值映射
     */
    public static Map<String, Object> parseObject(String rawBody) {
        if (StrUtil.isBlank(rawBody)) {
            return Map.of();
        }
        JsonNode node;
        try {
            node = STRICT_MAPPER.readTree(rawBody);
        } catch (JacksonException ex) {
            throw exception(DATA_API_REQUEST_INVALID, "JSON 请求体不合法或含重复属性");
        }
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw exception(DATA_API_REQUEST_INVALID, "JSON 请求体必须是对象");
        }
        try {
            return STRICT_MAPPER.convertValue(node, LinkedHashMap.class);
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_API_REQUEST_INVALID, "JSON 请求体不合法");
        }
    }
}
