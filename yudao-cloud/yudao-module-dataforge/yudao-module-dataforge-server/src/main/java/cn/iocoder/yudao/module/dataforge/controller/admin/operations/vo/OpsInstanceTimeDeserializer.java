package cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 运维快照中的本地日期文本与历史毫秒值使用同一契约，未知时间不推断为纪元。 */
public final class OpsInstanceTimeDeserializer extends ValueDeserializer<LocalDateTime> {
    /**
     * 读取运维快照日期，保留已持久化的文本精度与平台时区。
     * @param parser JSON 解析器
     * @param context 反序列化上下文
     * @return 已核验日期；未知零值为空
     */
    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            String value=parser.getString();
            if (value==null||value.isBlank()) return null;
            return LocalDateTime.parse(value.trim().replace(' ', 'T'));
        }
        if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            long value=parser.getLongValue();
            return value<=0?null:LocalDateTime.ofInstant(Instant.ofEpochMilli(value),ZoneId.of("Asia/Shanghai"));
        }
        return (LocalDateTime) context.handleUnexpectedToken(LocalDateTime.class,parser);
    }
}
