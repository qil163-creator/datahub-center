package cn.iocoder.yudao.framework.dbswitch.dbwriter.oscar;


import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarSqlNames;

import javax.sql.DataSource;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

/**
 * 神通数据库写入实现类
 *
 * @author tang
 */
public class OscarWriterImpl extends AbstractDatabaseWriter implements IDatabaseWriter {

    /**
     * 初始化 {@code OscarWriterImpl}及其父类状态。
     */
    public OscarWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Oscar";
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schema, String table, List<String> fields) {
        return "SELECT " + (fields == null || fields.isEmpty() ? "*" : OscarSqlNames.fields(fields))
                + " FROM " + OscarSqlNames.relation(schema, table) + " WHERE 1=2";
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    @Override
    protected String insertSql(List<String> fields) {
        return "INSERT INTO " + OscarSqlNames.relation(schemaName, tableName) + " (" + OscarSqlNames.fields(fields)
                + ") VALUES (" + String.join(",", java.util.Collections.nCopies(fields.size(), "?")) + ")";
    }

    /** 原生驱动带类型的 setObject 重载不执行其 JDBC 4.2 LocalDate 转换。 */
    @Override
    public long write(List<String> fieldNames, List<Object[]> recordValues) {
        if (recordValues.isEmpty()) {
            return 0;
        }
        int[] targetTypes = fieldTypes(fieldNames);
        var converted = BatchValueConverter.convert(recordValues, fieldNames.size(), batchLimits, (value, column) -> {
            if (targetTypes[column] != Types.DATE) {
                return value;
            }
            // 仅适配只含日期的输入。现有时间戳或含时间值保留其原生语义。
            if (value instanceof LocalDate || value instanceof String text && text.matches(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                LocalDate date = JdbcValueConversions.toDate(value);
                if (date.getYear() < 1 || date.getYear() > 9999) {
                    throw new IllegalArgumentException("Oscar date-only input requires a year in 1..9999");
                }
                return java.sql.Date.valueOf(date);
            }
            return value;
        });
        return super.write(fieldNames, converted);
    }

}
