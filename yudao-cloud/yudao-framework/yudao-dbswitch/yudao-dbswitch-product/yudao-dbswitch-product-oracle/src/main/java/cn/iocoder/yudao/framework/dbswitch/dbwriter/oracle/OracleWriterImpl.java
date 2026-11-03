package cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;

import javax.sql.DataSource;
import java.util.List;

/** Oracle 绑定将 LOB 资源限定在批次 JDBC 执行生命周期内。 */
public class OracleWriterImpl extends AbstractDatabaseWriter {

    /**
     * 初始化 {@code OracleWriterImpl}及其父类状态。
     */
    public OracleWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Oracle";
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schema, String table, List<String> fields) {
        return "SELECT " + (fields == null || fields.isEmpty() ? "*" : cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames.columns(
                fields))
                + " FROM " + cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames.relation(schema,
                table) + " WHERE 1=2";
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    @Override
    protected String insertSql(List<String> fields) {
        return "INSERT INTO " + cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames.relation(schemaName,
                tableName)
                + "(" + cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames.columns(fields) + ") VALUES("
                + String.join(",", java.util.Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fieldNames, List<Object[]> recordValues) {
        if (recordValues.isEmpty()) {
            return 0;
        }
        try (OracleLobBatch batch = new OracleLobBatch(recordValues, fieldTypes(fieldNames), batchLimits)) {
            return super.write(fieldNames, batch.rows());
        }
    }
}
