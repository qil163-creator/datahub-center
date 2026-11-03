package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 迁移钩子的有界不可变计划。解析复用组件现有
 * Druid 解析器和语句分类器，不作授权决策。
 * 明确不支持存储过程体、客户端指令和事务控制。
 */
public record SqlScriptPlan(ProductTypeEnum product, String digest, List<Step> steps) {

    private static final Pattern SUPPORTED_DDL = Pattern.compile(
            "(?is)^(?:CREATE\\s+(?:UNIQUE\\s+)?(?:TABLE|INDEX)\\b|ALTER\\s+(?:TABLE|INDEX)\\b"
                    + "|DROP\\s+(?:TABLE|INDEX)\\b|TRUNCATE\\b|COMMENT\\s+ON\\b)");

    /**
     * 初始化 {@code SqlScriptPlan}。
     */
    public SqlScriptPlan {
        steps = List.copyOf(steps);
    }

    public record Step(int ordinal, String sql, SqlStatementClassifier.Kind kind, int parameterCount) {

        /**
         * 使用给定参数初始化 {@code Step}。
         */
        public Step(int ordinal, String sql, SqlStatementClassifier.Kind kind) {
            this(ordinal, sql, kind, 0);
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "Step[ordinal=" + ordinal + ", kind=" + kind + "]";
        }
    }

    /**
     * 返回当前解析。
     */
    public static SqlScriptPlan parse(ProductTypeEnum product, String script) {
        if (script == null) {
            throw new IllegalArgumentException("SQL input is required");
        }
        return parse(product, new StringReader(script));
    }

    /**
     * 使用显式分隔符规划已支持的 SQL。空值和分号沿用既有契约；其他分隔符
     * 必须独占一行。此入口仍不接受过程体、事务控制或其他未验证语法。
     */
    public static SqlScriptPlan parse(ProductTypeEnum product, String script, String statementSeparator) {
        if (script == null) {
            throw new IllegalArgumentException("SQL input is required");
        }
        return parse(product, new StringReader(script), statementSeparator);
    }

    /**
     * 返回当前解析。
     */
    public static SqlScriptPlan parse(ProductTypeEnum product, Reader input, String statementSeparator) {
        if (SqlScriptInput.normalizeSeparator(statementSeparator).equals(";")) {
            return parse(product, input);
        }
        var source = SqlScriptInput.readStatements(product, input, statementSeparator);
        var steps = new ArrayList<Step>();
        for (String statement : source.statements()) {
            var plan = parse(product, statement);
            if (plan.steps().size() != 1) {
                throw new IllegalArgumentException("SQL separator must delimit one supported statement");
            }
            var step = plan.steps().getFirst();
            steps.add(new Step(steps.size() + 1, step.sql(), step.kind(), step.parameterCount()));
        }
        return new SqlScriptPlan(product, digest(source.script()), steps);
    }

    /**
     * 有界词法分段，不进行 AST 准入或对象授权，也不声明厂商执行兼容性。
     * 分号仅在正常词法态分段；其他 1–16 字符的明确标点分隔符（例如 /）
     * 必须独占一行，因此过程体内的分号得以保留。引号、注释和 PostgreSQL
     * dollar 体内的分隔符保持原样；PostgreSQL 家族拒绝与 dollar 引号冲突的 $$。
     * 总输入最多 100000 字符、单段最多 25000
     * 字符、可执行段最多 500 个。客户端指令和未闭合词法结构始终拒绝。
     * 调用方必须在执行任何一段前完成整脚本的权限及语法检查。
     */
    public static List<String> splitStatements(ProductTypeEnum product, String script, String statementSeparator) {
        if (script == null) {
            throw new IllegalArgumentException("SQL input is required");
        }
        return splitStatements(product, new StringReader(script), statementSeparator);
    }

    /** 不关闭调用方拥有的 Reader；与 String 入口使用相同预算和词法语义。 */
    public static List<String> splitStatements(ProductTypeEnum product, Reader input, String statementSeparator) {
        return SqlScriptInput.readStatements(product, input, statementSeparator).statements();
    }

    /**
     * 拒绝当前实现未支持的 {@code parse} 操作。
     */
    public static SqlScriptPlan parse(ProductTypeEnum product, Reader input) {
        if (!SqlStatementClassifier.supports(product)) {
            throw new UnsupportedOperationException("Migration scripts are unverified for this database product");
        }
        String script = SqlScriptInput.read(product, input);
        List<Step> steps = new ArrayList<>();
        try {
            var statements = SupportedSqlParser.parse(product, script);
            if (statements.isEmpty() || statements.size() > 500) {
                throw new IllegalArgumentException("Invalid migration script statement count");
            }
            for (var statement : statements) {
                var rendered = SupportedSqlParser.render(product, statement);
                String sql = rendered.sql().strip();
                if (sql.endsWith(";")) {
                    sql = sql.substring(0, sql.length() - 1);
                }
                var kind = SqlStatementClassifier.classify(product, sql).kind();
                if (kind == SqlStatementClassifier.Kind.OTHER || kind == SqlStatementClassifier.Kind.MULTI
                        || (kind == SqlStatementClassifier.Kind.DDL && !SUPPORTED_DDL.matcher(sql).find()
                        && !(product == ProductTypeEnum.POSTGRESQL && (statement instanceof SupportedPostgresStatementParser.SequenceCreate
                        || statement instanceof SupportedPostgresStatementParser.SequenceAlter)))) {
                    throw new IllegalArgumentException("Unsupported migration script statement");
                }
                steps.add(new Step(steps.size() + 1, sql, kind, rendered.parameterCount()));
            }
        } catch (RuntimeException failure) {
            // 解析器消息和原因可能包含完整 SQL，包括凭据。
            throw new IllegalArgumentException("Migration script cannot be planned using the supported syntax");
        }
        return new SqlScriptPlan(product, digest(script), steps);
    }

    /**
     * 为可信迁移生命周期钩子规划。此独立入口不扩展
     * 使用 {@link #parse} 的查询、DML 执行或只读 SQL 契约。
     * 解析仅建立有界语法计划，不保证数据库执行兼容性、
     * 对象授权或跨语句的事务原子性。
     */
    public static SqlScriptPlan parseMigrationHook(ProductTypeEnum product, String script) {
        if (script == null) {
            throw new IllegalArgumentException("SQL input is required");
        }
        return parseMigrationHook(product, new StringReader(script));
    }

    /**
     * 解析迁移前后钩子脚本并确定操作类别。
     */
    public static SqlScriptPlan parseMigrationHook(ProductTypeEnum product, Reader input) {
        return SqlMigrationHookParser.parse(product, input);
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    public static String digest(String script) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "SqlScriptPlan[product=" + product + ", digest=" + digest + ", statements=" + steps.size() + "]";
    }
}
