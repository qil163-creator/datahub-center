package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.Token;

import java.util.regex.Pattern;

/**
 * 仅供钩子使用的统计维护，刻意限制为单个带 schema 限定的对象。
 * SQLite 的通用 Druid 语句解析器不支持 ANALYZE，因此对此有限语法复用其方言
 * 词法分析器和 SQLName 解析器，不猜测其他方言。
 * 拒绝无目标、只有 schema 的目标、选项、列列表和额外目标。
 */
final class MigrationHookAnalyzeSql {

    // 这两个产品共用双引号原生标识符语法。Druid 的
    // SQLName 解析器还接受某些非原生引号；PostgreSQL 不得接受这些形式。
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    /**
     * 初始化 {@code MigrationHookAnalyzeSql}。
     */
    private MigrationHookAnalyzeSql() {
    }

    /**
     * 判断当前契约或能力是否准入给定操作。
     */
    static boolean admits(ProductTypeEnum product, String sql) {
        if (product != ProductTypeEnum.POSTGRESQL && product != ProductTypeEnum.SQLITE3) {
            return false;
        }
        var parser = SQLParserUtils.createExprParser(sql,
                product == ProductTypeEnum.POSTGRESQL ? DbType.postgresql : DbType.sqlite);
        var lexer = parser.getLexer();
        if (!lexer.identifierEquals("ANALYZE")) {
            return false;
        }
        lexer.nextToken();
        var target = parser.name();
        if (!(target instanceof SQLPropertyExpr object)
                || !(object.getOwner() instanceof SQLIdentifierExpr schema)
                || !nativeIdentifier(schema.getName())
                || !nativeIdentifier(object.getName())
                || lexer.token() != Token.EOF) {
            throw new IllegalArgumentException("Statistics hooks require one schema-qualified object");
        }
        return true;
    }

    /**
     * Druid 将带引号名称中的双写引号表示为反斜杠加引号。
     */
    private static boolean nativeIdentifier(String name) {
        // Druid 将带引号名称中的双写引号表示为反斜杠加引号。
        // 有界输入扫描器和词法分析器会校验原始标记；不要将规范化后的内容
        // 当作原始标识符语法重新渲染或校验。
        return IDENTIFIER.matcher(name).matches()
                || (name.length() > 2 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"');
    }
}
