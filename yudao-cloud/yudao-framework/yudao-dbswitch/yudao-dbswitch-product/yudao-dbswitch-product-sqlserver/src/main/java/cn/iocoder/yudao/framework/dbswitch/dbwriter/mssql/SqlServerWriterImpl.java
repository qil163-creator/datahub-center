package cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql;

import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerWriteColumns;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.util.CollectionUtils;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * SQLServer批量写入实现类
 *
 * @author jrl
 */
@Slf4j
public class SqlServerWriterImpl extends AbstractDatabaseWriter implements IDatabaseWriter {

    private final SqlServerIdentityTransfer identity;

    /**
     * 初始化 {@code SqlServerWriterImpl}及其父类状态。
     */
    public SqlServerWriterImpl(DataSource dataSource) {
        super(dataSource);
        identity = new SqlServerIdentityTransfer(dataSource);
    }

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    @Override
    public void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        super.configure(options);
        identity.configureTimeout(options.queryTimeoutSeconds());
    }

    /**
     * 配置标识列在当前搬运路径中的处理方式。
     */
    @Override
    public void configureIdentityTransfer(
            cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions options) {
        identity.configure(options);
    }

    /**
     * 在迁移前检查目标标识列策略和相关序列条件。
     */
    @Override
    public void preflightIdentityTransfer(String schema, String table, boolean exists) {
        identity.preflight(schema, table, exists);
    }

    /**
     * false 表示不支持且没有任何副作用。true 表示替换已完成并得到确认 结果。任何异常（包括 UNKNOWN）均不得回退到另一替换路径。
     */
    @Override
    public boolean prepareIdentityReplacement(
            cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityReplacement replacement) {
        return identity.replacement(replacement);
    }

    /** SQL Server 16 在截断、替换或任何数据语句前拒绝服务器生成列和 sql_variant 目标。 */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        var columns = SqlServerWriteColumns.read(dataSource, schema, table, runtimeOptions.queryTimeoutSeconds());
        if (columns != null) {
            columns.requireWritable(fields);
        }
        identity.prepare(schema, table, fields);
        super.prepareWrite(schema, table, fields);
        targetColumns = columns;
        prepared = true;
    }

    private SqlServerWriteColumns targetColumns;

    private boolean prepared;

    /**
     * 完成标识传输。
     */
    @Override
    public void completeIdentityTransfer() {
        identity.complete();
    }

    /**
     * 按已准备的 JDBC 类型和目标列配置写入当前数据批次。
     */
    @Override
    protected void batchWrite(List<String> fields, List<Object[]> rows, String sql, int[] types) {
        batchLimits.requireStatementParameters(types.length);
        cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor.execute(dataSource, context -> {
            identity.executeBatch(context, sql, rows, types);
            return null;
        });
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "SQL Server";
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schemaName, String tableName,
            List<String> fieldNames) {
        if (CollectionUtils.isEmpty(fieldNames)) {
            return "SELECT * FROM " + table(schemaName, tableName) + " WHERE 1=2";
        } else {
            return "SELECT " + columns(fieldNames) + " FROM " + table(schemaName, tableName) + " WHERE 1=2";
        }
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fieldNames, List<Object[]> recordValues) {
        if (recordValues.isEmpty()) {
            return 0;
        }

        recordValues = BatchValueConverter.convert(recordValues, fieldNames.size(), batchLimits);

        List<String> placeHolders = Collections.nCopies(fieldNames.size(), "?");
        String sqlInsert = "INSERT INTO " + table(schemaName, tableName) + " (" + columns(fieldNames)
                + ") VALUES (" + String.join(",", placeHolders) + ")";

        int[] argTypes = new int[fieldNames.size()];
        for (int i = 0; i < fieldNames.size(); ++i) {
            String col = fieldNames.get(i);
            argTypes[i] = this.columnType.get(col);
        }

    /*DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
    definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(
        this.dataSource);
    TransactionStatus status = transactionManager.getTransaction(definition);*/

        if (!prepared) {
            throw new IllegalStateException("SQL Server writer is not prepared");
        }
        if (targetColumns == null) {
            batchWrite(fieldNames,
                    cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqlServerTimeValues.bind(recordValues,
                            argTypes), sqlInsert, argTypes);
            return recordValues.size();
        }
        var order = targetColumns.columns(fieldNames);
        batchWrite(fieldNames, SqlServerWriteColumns.exactRows(recordValues, order, false), sqlInsert,
                SqlServerWriteColumns.bindingTypes(argTypes, order));
        return recordValues.size();
    /*try {
      int[] affects = jdbcTemplate.batchUpdate(sqlInsert, recordValues, argTypes);
      int affectCount = affects.length;
      recordValues.clear();
      transactionManager.commit(status);
      if (log.isDebugEnabled()) {
        log.debug("{} insert data affect count: {}", getDatabaseProductName(), affectCount);
      }
      return affectCount;
    } catch (Exception e) {
      transactionManager.rollback(status);
      throw e;
    }*/

    }

    /**
     * 返回表。
     */
    private static String table(String schema, String table) {
        return cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER, schema, table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private static String columns(List<String> names) {
        return names.stream().map(name -> cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                        .quotePhysicalIdentifier(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER,
                                name))
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
