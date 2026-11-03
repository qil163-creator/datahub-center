package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.io.StringReader;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 将已授权的受限 SELECT 封装为仅返回行数的执行查询。调用方仍负责授权；
 * 不返回业务投影，不重写内部 SQL，不改变参数出现顺序、排序或分页。
 * 无连接、配置或身份状态，不扩展原受限 SELECT 的语法能力。
 */
public final class SqlExecutionSummary {

    /**
     * 初始化 {@code SqlExecutionSummary}。
     */
    private SqlExecutionSummary() {
    }

    public record CountQuery(String authorizedSql, String sql, List<Integer> parameterOrder) {

        /**
         * 初始化 {@code CountQuery}。
         */
        public CountQuery {
            parameterOrder = List.copyOf(parameterOrder);
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "SqlExecutionSummary.CountQuery";
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code countQuery} 操作。
     */
    public static CountQuery countQuery(ProductTypeEnum product, String authorizedSql) {
        if (!RestrictedSelectGuard.supportsExactBinding(product)) {
            throw new UnsupportedOperationException("Count-only execution requires a verified SELECT product");
        }
        var input = SqlScriptInput.readStatements(product, new StringReader(authorizedSql), ";");
        if (input.statements().size() != 1) {
            throw new IllegalArgumentException("A single authorized SELECT is required");
        }
        String query = input.statements().getFirst();
        RestrictedSelectGuard.parse(product, query);
        var code = SqlScriptInput.readStatements(product, new StringReader(query), ";").executableText();
        int parameterCount = (int) code.chars().filter(character -> character == '?').count();
        var order = IntStream.range(0, parameterCount).boxed().toList();
        String aggregate = product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.OPENGAUSS
                ? "pg_catalog.COUNT" : "COUNT";
        // Newlines also terminate any trailing line comment before the closing parenthesis.
        return new CountQuery(authorizedSql,
                "SELECT " + aggregate + "(*) AS execution_row_count FROM (\n" + query + "\n) dbswitch_execution_summary",
                order);
    }
}
