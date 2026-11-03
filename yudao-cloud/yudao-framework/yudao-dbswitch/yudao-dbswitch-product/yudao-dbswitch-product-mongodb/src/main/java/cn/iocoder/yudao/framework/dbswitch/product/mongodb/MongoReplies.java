package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.SQLException;

/** 严格解释有界规范扩展 JSON 命令回执。 */
public final class MongoReplies {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 初始化 {@code MongoReplies}。
     */
    private MongoReplies() {
    }

    /**
     * 返回成功标记。
     */
    public static JsonNode success(MongoNativeSession session, byte[] reply) throws SQLException {
        try {
            JsonNode result = JSON.readTree(session.canonicalJson(reply));
            if (!result.isObject() || decimal(result.path("ok")).compareTo(BigDecimal.ONE) != 0) {
                throw new SQLException("MongoDB native command was rejected");
            }
            return result;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new SQLException("MongoDB native receipt is invalid");
        }
    }

    /**
     * 按当前值域约定解析或转换整数。
     */
    public static long integer(JsonNode node) throws SQLException {
        try {
            return decimal(node).longValueExact();
        } catch (ArithmeticException failure) {
            throw new SQLException("MongoDB receipt count is not an exact integer");
        }
    }

    /**
     * 按当前值域约定解析或转换精确小数。
     */
    private static BigDecimal decimal(JsonNode node) throws SQLException {
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isObject() && node.size() == 1) {
                for (String name : new String[]{"$numberInt", "$numberLong", "$numberDouble", "$numberDecimal"}) {
                    if (node.path(name).isTextual()) {
                        return new BigDecimal(node.get(name).textValue());
                    }
                }
            }
        } catch (NumberFormatException failure) {
            throw new SQLException("MongoDB numeric receipt is invalid");
        }
        throw new SQLException("MongoDB receipt lacks a numeric count");
    }

    /**
     * 检查 MongoDB 响应未包含写入错误或写关注错误。
     */
    public static void noWriteErrors(JsonNode result) throws SQLException {
        if (result.has("writeConcernError") || result.has("writeErrors") && !result.path("writeErrors").isEmpty()) {
            throw new SQLException("MongoDB write completion was not fully acknowledged");
        }
    }
}
