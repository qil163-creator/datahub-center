package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;

import java.util.Map;

/** 可信导入的名称展示，不将源系统身份当作本平台人员或可执行连接。 */
public final class MetaImportedDisplay {

    private final Map<?, ?> fields;

    /**
     * 使用给定字段初始化元数据导入已处理。
     */
    private MetaImportedDisplay(Map<?, ?> fields) {
        this.fields = fields;
    }

    /**
     * 读取元数据导入已处理的业务数据。
     */
    public static MetaImportedDisplay read(String json) {
        try {
            Map<?, ?> values = JsonUtils.parseObject(json, Map.class);
            return new MetaImportedDisplay(values != null && "qdata".equals(values.get("source")) ? values : Map.of());
        } catch (RuntimeException invalid) {
            return new MetaImportedDisplay(Map.of());
        }
    }

    /**
     * 读取元数据导入已处理中的指定值。
     */
    public String value(String field) {
        Object value = fields.get(field);
        return value instanceof String text && text.length() <= 256 ? text : null;
    }

    /**
     * 当前展示值为空时读取迁入快照中的对应字段。
     */
    public String fallback(String current, String field) {
        return current == null || current.isBlank() ? value(field) : current;
    }
}
