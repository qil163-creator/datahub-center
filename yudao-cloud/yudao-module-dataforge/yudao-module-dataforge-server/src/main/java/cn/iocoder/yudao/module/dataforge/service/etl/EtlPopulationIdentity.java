package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_TARGET_CONFIG_INVALID;

/** 完整受检数据集合的标识，包括隔离数据行；绝不能仅对获准数据行计算哈希。 */
final class EtlPopulationIdentity {

    private final List<String> fields;

    private final SortedSet<String> keys = new TreeSet<>();

    private long bytes;

    /**
     * 创建ETL总体身份实例。
     *
     * @param fields 字段定义
     */
    EtlPopulationIdentity(List<String> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > 16 || new HashSet<>(fields).size() != fields.size()) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "需配置唯一业务键字段");
        }
        this.fields = List.copyOf(fields);
    }

    /**
     * 添加ETL总体身份。
     *
     * @param row 当前行数据
     */
    void add(Map<String, Object> row) {
        List<String> parts = new ArrayList<>();
        for (String field : fields) {
            Object value = row.get(field);
            if (value == null || value instanceof Map || value instanceof Collection || value.getClass().isArray()) {
                throw exception(ETL_TARGET_CONFIG_INVALID, "业务键必须非空且为标量");
            }
            parts.add(value instanceof Number number ? "N:" + new java.math.BigDecimal(
                    number.toString()).stripTrailingZeros().toPlainString() : "S:" + value);
        }
        String key = JsonUtils.toJsonString(parts);
        bytes += key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (keys.size() >= 5000 || bytes > 1024 * 1024) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "业务键集合超过 5000 行或 1 MiB");
        }
        if (!keys.add(key)) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "本批业务键重复，无法建立可恢复身份");
        }
    }

    /**
     * 返回ETL总体身份的字段。
     *
     * @return ETL总体身份的字段结果集合
     */
    List<String> fields() {
        return fields;
    }

    /**
     * 计算输入内容摘要。
     *
     * @return 计算后的摘要或签名字符串
     */
    String hash() {
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of(fields, keys)));
    }
}
