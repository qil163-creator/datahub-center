package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.sql.Types;

/**
 * 已有 JSONB 编码器的产品内准入，只验证原生文本而不重新序列化：
 * 数值标记保留十进制拼写，SQL NULL 始终与 JSON null 区分。
 * 解析限制为深度 100、数值标记长度 1000 字符，这是经过测试的有限子集。
 * 共享工厂不保留连接、行值、解析器或驱动加载器。
 */
final class PostgresJsonbCopyValue {

    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(100).maxNumberLength(1000).build()).build();

    /**
     * 初始化 {@code PostgresJsonbCopyValue}。
     */
    private PostgresJsonbCopyValue() {
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    static String text(Object value, long maxCellBytes) {
        if (value == null) {
            return null;
        }
        try {
            if (!PostgresCopyAdmission.isTestedPgObject(value)) {
                throw invalid();
            }
            var type = value.getClass();
            if (!"jsonb".equals(type.getMethod("getType").invoke(value))) {
                throw invalid();
            }
            Object raw = type.getMethod("getValue").invoke(value);
            if (!(raw instanceof String text)) {
                throw invalid();
            }
            // 已有 COPY 转换在 UTF8 编码前拒绝原始 NUL 和未配对的 UTF16。
            JdbcValueConversions.forJdbcType(text, Types.VARCHAR);
            long bytes = payloadBytes(text);
            if (bytes > maxCellBytes || bytes > Integer.MAX_VALUE - 4L) {
                throw invalid();
            }
            try (var parser = JSON.createParser(text)) {
                JsonToken first = parser.nextToken();
                if (first == null) {
                    throw invalid();
                }
                int depth = 0;
                JsonToken token = first;
                do {
                    if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
                        JdbcValueConversions.forJdbcType(parser.getText(), Types.VARCHAR);
                    }
                    if (token.isNumeric()) {
                        // 绝不调用 getDoubleValue，也不通过浮点节点重新序列化十进制数。
                        var number = parser.getDecimalValue();
                        long scale = number.scale(), integerDigits = (long) number.precision() - scale;
                        if (scale < -131067 || scale > 16383 || integerDigits > 131068) {
                            throw invalid();
                        }
                    }
                    if (token.isStructStart()) {
                        depth++;
                    } else if (token.isStructEnd()) {
                        depth--;
                    }
                    if (depth == 0) {
                        break;
                    }
                    token = parser.nextToken();
                    if (token == null) {
                        throw invalid();
                    }
                } while (true);
                if (parser.nextToken() != null) {
                    throw invalid();
                }
            }
            return text;
        } catch (Exception | LinkageError invalid) {
            // Jackson 和厂商错误可能暴露行内容；调用方只看到不携带原始原因的异常契约。
            throw invalid();
        }
    }

    /** JSONB 版本字节加精确 UTF8 文本长度；预检时不分配编码缓冲。 */
    static long payloadBytes(String text) {
        long bytes = 1;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < 0x80) {
                bytes++;
            } else if (character < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static BatchRejectedException invalid() {
        return new BatchRejectedException("PostgreSQL COPY JSONB requires bounded valid native JSONB text");
    }
}
