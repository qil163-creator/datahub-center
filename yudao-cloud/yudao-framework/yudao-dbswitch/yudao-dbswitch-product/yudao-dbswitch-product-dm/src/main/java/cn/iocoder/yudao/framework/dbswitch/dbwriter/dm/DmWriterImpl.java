package cn.iocoder.yudao.framework.dbswitch.dbwriter.dm;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmDriverCompatibility;

import javax.sql.DataSource;
import java.util.List;

/**
 * DM数据库写入实现类
 *
 * @author jrl
 */
public class DmWriterImpl extends AbstractDatabaseWriter implements IDatabaseWriter {

    /**
     * 初始化 {@code DmWriterImpl}及其父类状态。
     */
    public DmWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "DM";
    }

    /**
     * 批量写入预处理。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        // 重新准备失败后，不能使上一次目标仍可写入。
        schemaName = null;
        tableName = null;
        columnType = null;
        // 此校验同时保护跨产品迁移，该流程有意复用已有目标，
        // 不构造同产品 NativeTargetPlan；处理器在 TRUNCATE 之前准备。
        DmSqlNames.relation(schema, table);
        try (var connection = dataSource.getConnection()) {
            DmDriverCompatibility.require(connection);
            try (var statement = connection.prepareStatement("SELECT sc.info2,o.info6 FROM SYS.SYSOBJECTS o "
                    + "JOIN SYS.SYSOBJECTS s ON s.id=o.schid JOIN SYS.SYSCOLUMNS sc ON sc.id=o.id "
                    + "WHERE s.name=? AND o.name=? AND o.type$='SCHOBJ' AND o.subtype$='UTAB'")) {
                statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                statement.setString(1, schema);
                statement.setString(2, table);
                try (var result = statement.executeQuery()) {
                    int columns = 0;
                    while (result.next()) {
                        int flags = result.getInt(1);
                        if (result.wasNull() || (flags & 1) != 0 || ++columns > 1024) {
                            throw new java.sql.SQLException("DM target identity protocol is unverified");
                        }
                        byte[] definition = result.getBytes(2);
                        if (definition == null || definition.length < 26 || definition[24] != 0 || definition[25] != 0) {
                            throw new java.sql.SQLException("DM target generator definition is unverified");
                        }
                    }
                    if (columns == 0) {
                        throw new java.sql.SQLException("DM target column evidence is unavailable");
                    }
                }
            }
        } catch (java.sql.SQLException | RuntimeException failure) {
            // 关闭或驱动异常可能暴露连接细节，不保留其原因链。
            throw new IllegalStateException("DM writes require a verified non-identity target");
        }
        super.prepareWrite(schema, table, fields);
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schema, String table, List<String> fields) {
        return "SELECT " + (fields == null || fields.isEmpty() ? "*" : DmSqlNames.columns(fields))
                + " FROM " + DmSqlNames.relation(schema, table) + " WHERE 1=2";
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    @Override
    protected String insertSql(List<String> fields) {
        return "INSERT INTO " + DmSqlNames.relation(schemaName, tableName) + " (" + DmSqlNames.columns(fields)
                + ") VALUES (" + String.join(",", java.util.Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fieldNames, List<Object[]> recordValues) {
        if (recordValues.isEmpty()) {
            return 0;
        }
        try (DmLobBatch batch = new DmLobBatch(recordValues, fieldTypes(fieldNames))) {
            return super.write(fieldNames, batch.rows());
        }
    }

}
