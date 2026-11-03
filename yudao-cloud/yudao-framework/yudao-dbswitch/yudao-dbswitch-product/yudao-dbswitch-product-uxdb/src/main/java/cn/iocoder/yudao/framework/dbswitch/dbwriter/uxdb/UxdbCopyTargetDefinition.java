package cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb;

import cn.iocoder.yudao.framework.dbswitch.core.service.PostgresCopyTargetDefinition;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLArrayDataType;
import com.alibaba.druid.sql.ast.SQLDataType;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.statement.*;

import java.math.BigInteger;
import java.util.*;

/** 共享 PostgreSQL CREATE 语法检查的 UXDB 子集，另行检查 UXDB 编码。 */
final class UxdbCopyTargetDefinition {

    /**
     * 初始化 {@code UxdbCopyTargetDefinition}。
     */
    private UxdbCopyTargetDefinition() {
    }

    /**
     * 检查 UXDB COPY 目标的建表语法、字段投影及编码条件。
     */
    static void validate(String schema, String table, List<String> statements, List<String> fields) {
        try {
            if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}") || table == null || !table.matches(
                    "[a-z_][a-z0-9_]{0,62}")
                    || fields == null || fields.size() > 1024) {
                throw new IllegalArgumentException();
            }
            PostgresCopyTargetDefinition.validate(schema, table, statements, fields);
            Set<String> columns = new LinkedHashSet<>();
            for (var statement : SQLUtils.parseStatements(String.join(";\n", statements), DbType.postgresql)) {
                if (statement instanceof SQLCreateTableStatement create) {
                    int constraints = 0;
                    for (var element : create.getTableElementList()) {
                        if (element instanceof SQLColumnDefinition column) {
                            String name = column.getNameAsString().replaceAll("^\"|\"$", "");
                            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}") || !columns.add(name)
                                    || column.getIdentity() != null || column.getCollateExpr() != null || column.getDefaultExpr() != null) {
                                throw new IllegalArgumentException();
                            }
                            var type = column.getDataType();
                            String nativeType = type.getName().toLowerCase(Locale.ROOT);
                            if (type instanceof SQLArrayDataType || !Set.of("smallint", "int2", "integer", "int",
                                            "int4", "bigint", "int8",
                                            "numeric", "decimal", "real", "float4", "double precision", "float8", "boolean",
                                            "bool", "text", "bytea", "date",
                                            "varchar", "character varying", "char", "character", "timestamp", "time")
                                    .contains(nativeType)) {
                                throw new IllegalArgumentException();
                            }
                            // 解析器在渲染类型中保留 TZ 语法，避免别名规范化时丢失时区。
                            if (type.toString().toLowerCase(Locale.ROOT).contains("with time zone")) {
                                throw new IllegalArgumentException();
                            }
                            validateModifiers(nativeType, type);
                            for (var constraint : column.getConstraints()) {
                                if (constraint instanceof SQLColumnPrimaryKey
                                        || constraint instanceof SQLColumnUniqueKey || constraint instanceof SQLColumnCheck) {
                                    constraints++;
                                }
                            }
                        } else if (element instanceof SQLUniqueConstraint key) {
                            if (key.getColumns().size() > 32) {
                                throw new IllegalArgumentException();
                            }
                            constraints++;
                        }
                    }
                    if (constraints > 256) {
                        throw new IllegalArgumentException();
                    }
                } else if (statement instanceof SQLCreateIndexStatement index) {
                    if (index.getItems().size() > 32) {
                        throw new IllegalArgumentException();
                    }
                } else if (!(statement instanceof SQLCommentStatement)) {
                    throw new IllegalArgumentException();
                }
            }
            if (!columns.equals(new HashSet<>(fields))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("UXDB COPY target CREATE layout is not verified");
        }
    }

    /** 调用方 CREATE 替换目标前，匹配 UxdbCursorMetadata.copyJdbcType 和 numericDomain。 */
    private static void validateModifiers(String name, SQLDataType type) {
        var arguments = type.getArguments();
        for (var argument : arguments) {
            if (!(argument instanceof SQLIntegerExpr)) {
                throw new IllegalArgumentException();
            }
        }
        switch (name) {
            case "numeric", "decimal" -> {
                if (arguments.isEmpty() || arguments.size() > 2) {
                    throw new IllegalArgumentException();
                }
                int precision = integer((SQLIntegerExpr) arguments.getFirst(), 1, 1000);
                if (arguments.size() == 2) {
                    integer((SQLIntegerExpr) arguments.get(1), 0, precision);
                }
            }
            case "varchar", "character varying" -> {
                // 省略边界会生成 typmod=-1，已验证的原生 COPY 布局会拒绝它。
                if (arguments.size() != 1) {
                    throw new IllegalArgumentException();
                }
                integer((SQLIntegerExpr) arguments.getFirst(), 1, 10_485_760);
            }
            case "char", "character" -> {
                // UXDB 省略 CHAR 长度时默认长度为 1，与省略 VARCHAR 长度不同。
                if (arguments.size() > 1) {
                    throw new IllegalArgumentException();
                }
                if (!arguments.isEmpty()) {
                    integer((SQLIntegerExpr) arguments.getFirst(), 1, 10_485_760);
                }
            }
            case "timestamp", "time" -> {
                if (arguments.size() > 1) {
                    throw new IllegalArgumentException();
                }
                if (!arguments.isEmpty()) {
                    integer((SQLIntegerExpr) arguments.getFirst(), 0, 6);
                }
            }
            default -> {
                if (!arguments.isEmpty()) {
                    throw new IllegalArgumentException();
                }
            }
        }
    }

    /**
     * 按当前值域约定解析或转换整数。
     */
    private static int integer(SQLIntegerExpr expression, int minimum, int maximum) {
        var value = new BigInteger(expression.getNumber().toString());
        if (value.compareTo(BigInteger.valueOf(minimum)) < 0 || value.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw new IllegalArgumentException();
        }
        return value.intValueExact();
    }
}
