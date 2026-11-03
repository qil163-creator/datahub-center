package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.jdk.StringDeserializer;

/** 拒绝非字符串的 JSON 令牌，避免对精确数值参数进行强制类型转换。 */
public final class StrictJsonStringDeserializer extends StringDeserializer {

    /**
     * 反序列化严格JSON字符串反序列化器。
     *
     * @param parser 解析器
     * @param context 当前处理上下文
     * @return 反序列化严格JSON字符串反序列化器文本
     */
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            return parser.getString();
        }
        return (String) context.handleUnexpectedToken(String.class, parser);
    }
}
