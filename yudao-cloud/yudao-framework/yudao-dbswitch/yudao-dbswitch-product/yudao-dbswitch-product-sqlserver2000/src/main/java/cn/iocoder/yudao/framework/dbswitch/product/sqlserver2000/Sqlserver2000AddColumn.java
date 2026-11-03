package cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerAddColumn;

import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/** SQL Server 2000 的可空 ADD，绝不隐式继承现代 SQL Server 类型。 */
public final class Sqlserver2000AddColumn {

    public static final String REASON = "SQLSERVER2000_LIMITED_NULLABLE_ADD_INT_DECIMAL_NVARCHAR_NO_DEFAULT_QUOTED";

    private static final Pattern DECIMAL = Pattern.compile("DECIMAL\\(([0-9]{1,2}),([0-9]{1,2})\\)");

    private static final Pattern NVARCHAR = Pattern.compile("NVARCHAR\\(([0-9]{1,4})\\)");

    /**
     * 初始化 {@code Sqlserver2000AddColumn}。
     */
    private Sqlserver2000AddColumn() {
    }

    /**
     * 根据当前SQL Server 2000方言生成 SQL 语句或片段。
     */
    public static String sql(String schema, String table, ColumnDescription column,
            Function<ColumnMetaData, String> fieldDefinition) {
        if (column == null || fieldDefinition == null) {
            throw new IllegalArgumentException("SQL Server 2000 add column requires a column and field definition");
        }
        return SqlServerAddColumn.sql(schema, table, column, meta -> {
            String definition = fieldDefinition.apply(meta);
            String prefix = " " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SQLSERVER,
                    column.getFieldName()) + "  ";
            if (definition == null || !definition.startsWith(prefix)) {
                throw new IllegalArgumentException(
                        "SQL Server 2000 field definition did not quote the physical column");
            }
            String type = definition.substring(prefix.length()).strip().toUpperCase(Locale.ROOT);
            if (!limitedType(type)) {
                unsupported("SQL Server 2000 nullable add only supports INT, DECIMAL(p,s) and NVARCHAR(n<=4000)");
            }
            return prefix + type;
        }) + " NULL";
    }

    /**
     * 判断当前原生类型是否处于已验证的有限类型集合。
     */
    private static boolean limitedType(String type) {
        if ("INT".equals(type)) {
            return true;
        }
        var decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1)), scale = Integer.parseInt(decimal.group(2));
            return precision >= 1 && precision <= 38 && scale <= precision;
        }
        var text = NVARCHAR.matcher(type);
        if (text.matches()) {
            int length = Integer.parseInt(text.group(1));
            return length >= 1 && length <= 4000;
        }
        return false;
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String message) throws E {
        throw (E) new SQLFeatureNotSupportedException(message, "0A000");
    }
}
