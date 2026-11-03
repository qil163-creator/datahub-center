package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 将正常 SQL 词法态的 ${name} 编译为 JDBC ?，仅保留参数名顺序，不接收或渲染参数值。
 * 用于在支持 JDBC 占位符的语法/授权入口完成检查后，恢复外部执行器的命名占位符。
 * 不允许原始替换 !{}、时间模板 $[]、裸 ?/$n，以及引号或注释内的命名占位符。
 * 此类不进行 SQL 授权，不声明厂商执行兼容性；调用方须完成语句、对象与值类型校验。
 */
public final class NamedSqlBindings {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private final String sql;

    private final List<String> parameterNames;

    /**
     * 初始化 {@code NamedSqlBindings}。
     */
    private NamedSqlBindings(String sql, List<String> parameterNames) {
        this.sql = sql;
        this.parameterNames = List.copyOf(parameterNames);
    }

    /**
     * 返回SQL 文本。
     */
    public String sql() {
        return sql;
    }

    /** 按出现顺序保存，同名参数重复出现时保留每次出现。 */
    public List<String> parameterNames() {
        return parameterNames;
    }

    /**
     * 返回当前编译。
     */
    public static NamedSqlBindings compile(ProductTypeEnum product, String input) {
        var source = source(product, input);
        String executable = source.executableText();
        var names = new ArrayList<String>();
        var compiled = new StringBuilder(input.length());
        for (int offset = 0; offset < input.length(); offset++) {
            rejectRawTemplate(input, offset);
            if (input.startsWith("${", offset)) {
                int end = input.indexOf('}', offset + 2);
                if (end < 0 || end - offset > 66) {
                    throw invalid();
                }
                String name = input.substring(offset + 2, end);
                if (!NAME.matcher(name).matches()
                        || !input.regionMatches(offset, executable, offset, end - offset + 1)) {
                    throw invalid();
                }
                compiled.append('?');
                names.add(name);
                offset = end;
            } else {
                if (executable.charAt(offset) == '?' || positionalParameter(input, executable, offset)) {
                    throw invalid();
                }
                compiled.append(input.charAt(offset));
            }
        }
        return new NamedSqlBindings(compiled.toString(), names);
    }

    /**
     * 只替换正常词法态的 JDBC ?，字面量/注释中的 ? 保持原样。
     * SQL 原样时保留出现顺序。已验证的受限 SELECT 重写按谓词/槽位身份恢复，
     * 因此 LIMIT/OFFSET 渲染重排不会换参；不支持任意表达式/语句重写。
     * 角色或数量不一致时失败，绝不插入参数值。
     */
    public String restore(ProductTypeEnum product, String rewrittenSql) {
        return restoreNames(product, rewrittenSql, restorationNames(product, rewrittenSql));
    }

    /**
     * 接受正式授权提供方返回的完整 AST 参数排列。每个原始参数索引必须恰好出现一次；
     * 该契约不授权 SQL，本地调用方不得自行猜测排列或以参数数量代替授权证明。
     */
    public String restoreOrdered(ProductTypeEnum product, String rewrittenSql, List<Integer> parameterOrder) {
        if (parameterOrder == null || parameterOrder.size() != parameterNames.size()
                || parameterOrder.stream()
                .anyMatch(index -> index == null || index < 0 || index >= parameterNames.size())
                || new java.util.HashSet<>(parameterOrder).size() != parameterNames.size()) {
            throw invalid();
        }
        return restoreNames(product, rewrittenSql, parameterOrder.stream().map(parameterNames::get).toList());
    }

    /**
     * 恢复名称集合。
     */
    private String restoreNames(ProductTypeEnum product, String rewrittenSql, List<String> rewrittenNames) {
        var source = source(product, rewrittenSql);
        var restored = new StringBuilder(rewrittenSql.length());
        String executable = source.executableText();
        int parameter = 0;
        for (int offset = 0; offset < rewrittenSql.length(); offset++) {
            rejectRawTemplate(rewrittenSql, offset);
            if (rewrittenSql.startsWith("${", offset)
                    || positionalParameter(rewrittenSql, executable, offset)) {
                throw invalid();
            }
            if (executable.charAt(offset) == '?') {
                if (parameter >= rewrittenNames.size()) {
                    throw invalid();
                }
                restored.append("${").append(rewrittenNames.get(parameter++)).append('}');
            } else {
                restored.append(rewrittenSql.charAt(offset));
            }
        }
        if (parameter != parameterNames.size()) {
            throw invalid();
        }
        String result = restored.toString();
        // 重写后恢复的文本仍受同一输入及单段预算约束。
        source(product, result);
        return result;
    }

    /**
     * AST/词法失败也不可把输入或解析器原因透传到业务日志。
     */
    private List<String> restorationNames(ProductTypeEnum product, String rewrittenSql) {
        if (parameterNames.isEmpty() || sql.equals(rewrittenSql)) {
            return parameterNames;
        }
        try {
            var original = RestrictedSelectGuard.parameterRoles(product, sql);
            var rewritten = RestrictedSelectGuard.parameterRoles(product, rewrittenSql);
            if (original.size() != parameterNames.size() || !original.keySet().equals(rewritten.keySet())) {
                throw invalid();
            }
            var names = new ArrayList<String>(java.util.Collections.nCopies(parameterNames.size(), null));
            original.forEach((role, sourceIndex) -> names.set(rewritten.get(role), parameterNames.get(sourceIndex)));
            if (names.contains(null)) {
                throw invalid();
            }
            return List.copyOf(names);
        } catch (RuntimeException failure) {
            // AST/词法失败也不可把输入或解析器原因透传到业务日志。
            throw invalid();
        }
    }

    /**
     * 返回源端。
     */
    private static SqlScriptInput.HookInput source(ProductTypeEnum product, String input) {
        if (input == null) {
            throw new IllegalArgumentException("SQL input is required");
        }
        return SqlScriptInput.readStatements(product, new StringReader(input), ";");
    }

    /**
     * 拒绝未经受控参数渲染的原始模板内容。
     */
    private static void rejectRawTemplate(String sql, int offset) {
        if (sql.startsWith("!{", offset) || sql.startsWith("$[", offset)) {
            throw invalid();
        }
    }

    /**
     * 为当前位置生成参数占位符。
     */
    private static boolean positionalParameter(String sql, String executable, int offset) {
        return executable.charAt(offset) == '$' && offset + 1 < sql.length()
                && sql.charAt(offset + 1) >= '0' && sql.charAt(offset + 1) <= '9';
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SQL named bindings contain unsupported syntax or a parameter mismatch");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "NamedSqlBindings[parameters=" + parameterNames.size() + "]";
    }
}
