package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.CollectionUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据库写入抽象基类
 * <p>
 * 职责：全量迁移场景下批量写入目标表的通用实现。prepareWrite 阶段查询并缓存目标表
 * 各字段的 JDBC 类型（columnType），write 阶段拼接批量 INSERT 语句并用 JDBC 批处理执行。
 * <p>
 * 协作关系：由 产品 Provider 按目标数据库类型创建对应子类实例，
 * 供 MigrationHandler 在全量覆盖同步中调用。子类通常只需覆盖：
 * getDatabaseProductName（产品名）、selectTableMetaDataSqlString（元数据探测 SQL 的引号方言）、
 * 以及必要时覆盖 write/batchWrite（如 PostgresCompatibleCopyWriterImpl 改用 COPY 协议）
 *
 * @author jrl
 */
@Slf4j
public abstract class AbstractDatabaseWriter implements IDatabaseWriter {

    protected cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits batchLimits =
            cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits.defaults();

    /**
     * prepare/write 前配置操作实例；提供方必须显式接受自定义限额。
     */
    @Override
    public void configureBatchLimits(cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits limits) {
        this.batchLimits = java.util.Objects.requireNonNull(limits, "limits");
    }

    protected cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions runtimeOptions =
            cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults();

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    @Override
    public void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        runtimeOptions = java.util.Objects.requireNonNull(options, "options");
    }


    /** 目标端数据源 */
    protected DataSource dataSource;

    /** 基于目标端数据源的 JdbcTemplate */
    protected JdbcTemplate jdbcTemplate;

    /** 目标 schema 名（prepareWrite 时赋值） */
    protected String schemaName;

    /** 目标表名（prepareWrite 时赋值） */
    protected String tableName;

    /** 目标表“字段名 -> JDBC 类型”映射（prepareWrite 时查询填充） */
    protected Map<String, Integer> columnType;

    /**
     * 初始化 {@code AbstractDatabaseWriter}。
     */
    public AbstractDatabaseWriter(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
        this.schemaName = null;
        this.tableName = null;
        this.columnType = null;
    }

    /**
     * 获取数据源对象。
     */
    @Override
    public DataSource getDataSource() {
        return this.dataSource;
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据），
     * 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用；
     * 查询失败或表无字段时抛出运行时异常
     *
     * @param schemaName 目标 schema 名
     * @param tableName  目标表名
     * @param fieldNames 需要写入的字段列表（为空时探测全部字段）
     */
    @Override
    public void prepareWrite(String schemaName, String tableName, List<String> fieldNames) {
        String sql = this.selectTableMetaDataSqlString(schemaName, tableName, fieldNames);
        Map<String, Integer> columnMetaData = new HashMap<>();
        jdbcTemplate.execute((Connection conn) -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);) {
                ResultSetMetaData rsMetaData = rs.getMetaData();
                for (int i = 0, len = rsMetaData.getColumnCount(); i < len; i++) {
                    columnMetaData.put(rsMetaData.getColumnName(i + 1), rsMetaData.getColumnType(i + 1));
                }

                return true;
            } catch (Exception e) {
                throw new RuntimeException(
                        String.format("获取表:%s.%s 的字段的元信息时失败. 请联系 DBA 核查该库、表信息.", schemaName,
                                tableName), e);
            }
        });

        this.schemaName = schemaName;
        this.tableName = tableName;
        this.columnType = Objects.requireNonNull(columnMetaData);
        if (this.columnType.isEmpty()) {
            throw new RuntimeException(
                    String.format("获取表:%s.%s 的字段的元信息时失败. 请联系 DBA 核查该库、表信息.", schemaName,
                            tableName));
        }

    }

    /**
     * 生成探测目标表字段元数据的 SQL（WHERE 1=2 不取数据，仅取元信息）；
     * 默认使用双引号包裹标识符，引号方言不同的数据库由子类覆盖
     */
    protected String selectTableMetaDataSqlString(String schemaName, String tableName,
            List<String> fieldNames) {
        if (CollectionUtils.isEmpty(fieldNames)) {
            return String.format("SELECT *  FROM \"%s\".\"%s\"  WHERE 1=2", schemaName, tableName);
        } else {
            return String.format("SELECT \"%s\"  FROM \"%s\".\"%s\"  WHERE 1=2",
                    StringUtils.join(fieldNames, "\",\""), schemaName, tableName);
        }
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出），由子类实现
     */
    protected abstract String getDatabaseProductName();

	/*protected TransactionDefinition getTransactionDefinition() {
		DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
		definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		return definition;
	}*/

    /**
     * 批量写入一批数据（默认实现：批量 INSERT）
     * <p>
     * 主要流程：
     * 1. 拼接带 ? 占位符的 INSERT 语句（标识符用双引号包裹）；
     * 2. 按 prepareWrite 缓存的字段类型组装各占位符的 JDBC 类型数组；
     * 3. 交给 batchWrite 以 JDBC 批处理方式执行并提交
     *
     * @param fieldNames   写入的字段名列表（顺序与记录数组一致）
     * @param recordValues 一批记录，每条为与字段列表等长的对象数组
     * @return 实际写入的行数
     */
    @Override
    public long write(List<String> fieldNames, List<Object[]> recordValues) {
        if (recordValues.isEmpty()) {
            return 0;
        }

        String sqlInsert = insertSql(fieldNames);
        int[] argTypes = fieldTypes(fieldNames);
        recordValues = BatchValueConverter.convert(recordValues, fieldNames.size(), batchLimits);

        batchWrite(fieldNames, recordValues, sqlInsert, argTypes);

        return recordValues.size();

		/*PlatformTransactionManager transactionManager = new DataSourceTransactionManager(
				this.dataSource);
		TransactionStatus status = transactionManager.getTransaction(getTransactionDefinition());

		try {
			//int[] affects = jdbcTemplate.batchUpdate(sqlInsert, recordValues, argTypes);
			//int affectCount = Arrays.stream(affects).sum();
			jdbcTemplate.batchUpdate(sqlInsert, recordValues, argTypes);
			transactionManager.commit(status);
			int affectCount = recordValues.size();
			recordValues.clear();
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
     * 与增量同步共用单批 JDBC 事务边界；保留自定义参数绑定，不自动逐行重投。
     * 已尝试写入后的异常保留未知结果，不以本地回滚/清理推导远端未提交。
     */
    protected void batchWrite(List<String> fieldNames, List<Object[]> recordValues, String sqlInsert, int[] argTypes) {
        batchLimits.requireStatementParameters(argTypes.length);
        JdbcBatchExecutor.execute(dataSource, sqlInsert, recordValues, argTypes, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    protected String insertSql(List<String> fieldNames) {
        return String.format("INSERT INTO \"%s\".\"%s\" ( \"%s\" ) VALUES ( %s )",
                schemaName, tableName, StringUtils.join(fieldNames, "\",\""),
                StringUtils.join(Collections.nCopies(fieldNames.size(), "?"), ","));
    }

    /**
     * 按当前字段顺序生成 JDBC 类型数组。
     */
    protected int[] fieldTypes(List<String> fieldNames) {
        int[] types = new int[fieldNames.size()];
        for (int i = 0; i < fieldNames.size(); i++) {
            types[i] = columnType.get(fieldNames.get(i));
        }
        return types;
    }

}
