package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.Map;
import java.util.Objects;

/**
 * SQL 血缘分析请求。
 *
 * @param product            数据库产品，决定解析方言与标识符折叠规则
 * @param defaultSchema      未限定表名使用的命名空间，建议由 {@code DatabaseNamespaceResolver} 解析；可为 null
 * @param script             待分析脚本，可包含多条语句
 * @param parameters         固定值参数，按 {@code ${name}} 原文替换；未提供的参数保留为占位
 * @param statementSeparator 语句分隔符：{@code ;}（默认）或单独成行的 {@code /}
 * @param catalog            表结构提供者
 * @param limits             分析上限
 */
public record SqlLineageRequest(ProductTypeEnum product, String defaultSchema, String script,
                                Map<String, String> parameters, String statementSeparator,
                                LineageCatalog catalog, SqlLineageLimits limits) {

    /**
     * 补齐可选参数的默认值。
     */
    public SqlLineageRequest {
        Objects.requireNonNull(product, "product");
        script = script == null ? "" : script;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        statementSeparator = "/".equals(statementSeparator) ? "/" : ";";
        catalog = catalog == null ? LineageCatalog.none() : catalog;
        limits = limits == null ? SqlLineageLimits.defaults() : limits;
        if (defaultSchema != null && defaultSchema.isBlank()) {
            defaultSchema = null;
        }
    }

    /**
     * 使用默认参数创建请求。
     */
    public static SqlLineageRequest of(ProductTypeEnum product, String defaultSchema, String script) {
        return new SqlLineageRequest(product, defaultSchema, script, null, null, null, null);
    }

    /**
     * 返回替换了固定值参数的请求。
     */
    public SqlLineageRequest withParameters(Map<String, String> value) {
        return new SqlLineageRequest(product, defaultSchema, script, value, statementSeparator, catalog, limits);
    }

    /**
     * 返回替换了表结构提供者的请求。
     */
    public SqlLineageRequest withCatalog(LineageCatalog value) {
        return new SqlLineageRequest(product, defaultSchema, script, parameters, statementSeparator, value, limits);
    }

    /**
     * 返回替换了语句分隔符的请求。
     */
    public SqlLineageRequest withStatementSeparator(String value) {
        return new SqlLineageRequest(product, defaultSchema, script, parameters, value, catalog, limits);
    }

    /**
     * 返回替换了分析上限的请求。
     */
    public SqlLineageRequest withLimits(SqlLineageLimits value) {
        return new SqlLineageRequest(product, defaultSchema, script, parameters, statementSeparator, catalog, value);
    }
}
