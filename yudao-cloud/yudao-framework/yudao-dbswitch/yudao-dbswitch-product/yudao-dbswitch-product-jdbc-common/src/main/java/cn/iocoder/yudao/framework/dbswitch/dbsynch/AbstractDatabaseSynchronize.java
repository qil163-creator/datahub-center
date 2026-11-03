package cn.iocoder.yudao.framework.dbswitch.dbsynch;

import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 数据同步抽象基类
 * <p>
 * 职责：变化量（增量）同步的写入端基类。在 prepare 阶段一次性生成目标表的
 * INSERT / UPDATE / DELETE 三种 PreparedStatement 语句模板及各自的参数 JDBC 类型数组，
 * 之后由 executeInsert / executeUpdate / executeDelete 以 JDBC 批处理方式高效执行。
 * <p>
 * 协作关系：由 产品 Provider 按目标数据库类型创建对应子类；
 * MigrationHandler 在变化量同步中把 ChangeCalculatorService 计算出的
 * 增/删/改记录分批交给本类执行。SQL 语句的方言差异（引号、占位符写法）由子类通过
 * getInsertPrepareStatementSql / getUpdatePrepareStatementSql / getDeletePrepareStatementSql 提供
 *
 * @author jrl
 */
@Slf4j
public abstract class AbstractDatabaseSynchronize implements IDatabaseSynchronize {

    protected cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions runtimeOptions =
            cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults();

    /**
     * 在 prepare 前配置新操作；自定义选项不得被静默忽略。
     */
    @Override
    public void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        this.runtimeOptions = java.util.Objects.requireNonNull(options, "options");
    }

    protected cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits batchLimits =
            cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits.defaults();

    /**
     * 设置写入批次的行数、字节数及绑定参数限制。
     */
    @Override
    public void configureBatchLimits(cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits limits) {
        this.batchLimits = java.util.Objects.requireNonNull(limits, "limits");
    }

    /** 目标端数据源 */
    protected DataSource dataSource;

    /** 基于目标端数据源的 JdbcTemplate */
    protected JdbcTemplate jdbcTemplate;
    /*private PlatformTransactionManager transactionManager;*/

    /** 目标表“字段名 -> JDBC 类型”映射，prepare 阶段查询填充 */
    private Map<String, Integer> columnType;

    /** 同步字段的顺序（与记录数组的下标一一对应） */
    protected List<String> fieldOrders;

    /** 主键字段的顺序 */
    protected List<String> pksOrders;

    /** 预生成的 INSERT 语句模板（含 ? 占位符） */
    protected String insertStatementSql;

    /** 预生成的 UPDATE 语句模板（SET 非主键字段，WHERE 主键） */
    protected String updateStatementSql;

    /** 预生成的 DELETE 语句模板（WHERE 主键） */
    protected String deleteStatementSql;

    /** INSERT 各占位符的 JDBC 类型（按字段顺序） */
    protected int[] insertArgsType;

    /** UPDATE 各占位符的 JDBC 类型（先非主键字段后主键） */
    protected int[] updateArgsType;

    /** DELETE 各占位符的 JDBC 类型（按主键顺序） */
    protected int[] deleteArgsType;

    /** 目标 schema 名 */
    protected String schemaName;

    /** 目标表名 */
    protected String tableName;

    /**
     * 初始化 {@code AbstractDatabaseSynchronize}。
     */
    public AbstractDatabaseSynchronize(DataSource ds) {
        this.dataSource = ds;
        this.jdbcTemplate = new JdbcTemplate(ds);
        /*this.transactionManager = new DataSourceTransactionManager(ds);*/
        this.columnType = new HashMap<>();
    }

    /**
     * 获取数据源对象。
     */
    @Override
    public DataSource getDataSource() {
        return this.jdbcTemplate.getDataSource();
    }

	/*protected TransactionDefinition getTransactionDefinition() {
		DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
		definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		return definition;
	}
*/

    /**
     * 获取查询列元信息的SQL语句
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @return SQL语句
     */
    public abstract String getColumnMetaDataSql(String schemaName, String tableName);

    /**
     * 生成Insert操作的SQL语句
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param fieldNames 字段列表
     * @return Insert操作的SQL语句
     */
    public abstract String getInsertPrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames);

    /**
     * 生成Update操作的SQL语句
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param fieldNames 字段列表
     * @param pks        主键列表
     * @return Update操作的SQL语句
     */
    public abstract String getUpdatePrepareStatementSql(String schemaName, String tableName,
            List<String> fieldNames, List<String> pks);

    /**
     * 生成Delete操作的SQL语句
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param pks        主键列表
     * @return Delete操作的SQL语句
     */
    public abstract String getDeletePrepareStatementSql(String schemaName, String tableName,
            List<String> pks);

    /**
     * 同步准备（主键比对模式）
     * <p>
     * 主要流程：
     * 1. 校验字段列表与主键列表非空，且字段列表包含全部主键；
     * 2. 执行子类提供的元数据查询 SQL，从结果集元数据中缓存目标表“字段名 -> JDBC 类型”；
     * 3. 记录字段顺序与主键顺序；
     * 4. 预生成 INSERT/UPDATE/DELETE 三种语句模板；
     * 5. 按各语句占位符的顺序预先计算参数 JDBC 类型数组
     *    （UPDATE 的顺序为：先非主键字段、后主键字段，与语句 SET/WHERE 结构一致）
     *
     * @param schemaName 目标 schema 名
     * @param tableName  目标表名
     * @param fieldNames 同步的字段列表
     * @param pks        主键字段列表
     */
    @Override
    public void prepare(String schemaName, String tableName, List<String> fieldNames,
            List<String> pks) {
        if (fieldNames.isEmpty() || pks.isEmpty() || fieldNames.size() < pks.size()) {
            throw new IllegalArgumentException("字段列表和主键列表不能为空，或者字段总个数应不小于主键总个数");
        }

        if (!fieldNames.containsAll(pks)) {
            throw new IllegalArgumentException("字段列表必须包含主键列表");
        }

        // 查询目标表字段的 JDBC 类型并缓存
        String sql = this.getColumnMetaDataSql(schemaName, tableName);
        columnType.clear();

        this.jdbcTemplate.execute((Connection conn) -> {
            Statement stmt = null;
            ResultSet rs = null;
            try {
                stmt = conn.createStatement();
                stmt.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                rs = stmt.executeQuery(sql);
                ResultSetMetaData rsMetaData = rs.getMetaData();
                for (int i = 0, len = rsMetaData.getColumnCount(); i < len; i++) {
                    columnType.put(rsMetaData.getColumnLabel(i + 1), rsMetaData.getColumnType(i + 1));
                }

                return true;
            } catch (Exception e) {
                throw new RuntimeException(
                        String.format("获取表:%s.%s 的字段的元信息时失败. 请联系 DBA 核查该库、表信息.", schemaName,
                                tableName), e);
            } finally {
                JdbcUtils.closeResultSet(rs);
                JdbcUtils.closeStatement(stmt);
            }
        });

        this.schemaName = schemaName;
        this.tableName = tableName;
        this.fieldOrders = new ArrayList<>(fieldNames);
        this.pksOrders = new ArrayList<>(pks);

        // 预生成三种语句模板
        this.insertStatementSql = this.getInsertPrepareStatementSql(schemaName, tableName, fieldNames);
        this.updateStatementSql = this
                .getUpdatePrepareStatementSql(schemaName, tableName, fieldNames, pks);
        this.deleteStatementSql = this.getDeletePrepareStatementSql(schemaName, tableName, pks);

        // 按语句占位符顺序预计算各参数的 JDBC 类型
        insertArgsType = new int[fieldNames.size()];
        for (int k = 0; k < fieldNames.size(); ++k) {
            String field = fieldNames.get(k);
            insertArgsType[k] = this.columnType.get(field);
        }

        updateArgsType = new int[fieldNames.size()];
        int idx = 0;
        for (String field : fieldNames) {
            if (!pks.contains(field)) {
                updateArgsType[idx++] = this.columnType.get(field);
            }
        }
        for (String pk : pks) {
            updateArgsType[idx++] = this.columnType.get(pk);
        }

        deleteArgsType = new int[pks.size()];
        for (int j = 0; j < pks.size(); ++j) {
            String pk = pks.get(j);
            deleteArgsType[j] = this.columnType.get(pk);
        }
    }


    /**
     * 批量执行 INSERT：记录数组的字段顺序与语句占位符一致，直接批量执行
     *
     * @param records 待插入记录（每条与字段列表等长）
     * @return 处理的记录数
     */
    @Override
    public long executeInsert(List<Object[]> records) {
        if (records.isEmpty()) {
            return 0;
        }
        records = BatchValueConverter.convert(records, fieldOrders.size(), batchLimits);
        batchInsert(records, this.insertStatementSql, this.insertArgsType);
        return records.size();
		/*TransactionStatus status = transactionManager.getTransaction(getTransactionDefinition());
		if (log.isDebugEnabled()) {
			log.debug("Execute Insert SQL : {}", this.insertStatementSql);
		}

		try {
			int[] affects = jdbcTemplate
					.batchUpdate(this.insertStatementSql, records, this.insertArgsType);
			transactionManager.commit(status);
			return affects.length;
		} catch (Exception e) {
			transactionManager.rollback(status);
			throw e;
		}*/
    }

    /**
     * 批量执行 UPDATE
     * <p>
     * UPDATE 语句结构为 SET 非主键字段=? WHERE 主键=?，因此需要把每条记录的参数
     * 重排为“先非主键字段值、后主键值”的顺序，再批量执行
     *
     * @param records 待更新记录（字段顺序与 fieldOrders 一致）
     * @return 处理的记录数
     */
    @Override
    public long executeUpdate(List<Object[]> records) {
        if (records.isEmpty()) {
            return 0;
        }
        records = BatchValueConverter.convert(records, fieldOrders.size(), batchLimits);
        // 参数重排：非主键字段值在前（对应 SET 子句），主键值在后（对应 WHERE 子句）
        List<Object[]> datas = new LinkedList<>();
        for (Object[] r : records) {

            Object[] nr = new Object[this.fieldOrders.size()];
            int idx = 0;

            for (int i = 0; i < this.fieldOrders.size(); ++i) {
                String field = this.fieldOrders.get(i);
                if (!this.pksOrders.contains(field)) {
                    int index = this.fieldOrders.indexOf(field);
                    nr[idx++] = r[index];
                }
            }

            for (String pk : this.pksOrders) {
                int index = this.fieldOrders.indexOf(pk);
                nr[idx++] = r[index];
            }

            datas.add(nr);
        }

        batchUpdate(datas, this.updateStatementSql, this.updateArgsType);
        return records.size();
		/*TransactionStatus status = transactionManager.getTransaction(getTransactionDefinition());
		if (log.isDebugEnabled()) {
			log.debug("Execute Update SQL : {}", this.updateStatementSql);
		}

		try {
			int[] affects = jdbcTemplate.batchUpdate(this.updateStatementSql, datas, this.updateArgsType);

			transactionManager.commit(status);
			return affects.length;
		} catch (Exception e) {
			transactionManager.rollback(status);
			throw e;
		}*/
    }

    /**
     * 批量执行 DELETE：从完整记录中按主键顺序抽取主键值作为 WHERE 条件参数，再批量执行
     *
     * @param records 待删除记录（字段顺序与 fieldOrders 一致）
     * @return 处理的记录数
     */
    @Override
    public long executeDelete(List<Object[]> records) {
        // 仅抽取主键值作为 DELETE 语句的 WHERE 参数
        List<Object[]> datas = new LinkedList<>();
        for (Object[] r : records) {
            Object[] nr = new Object[this.pksOrders.size()];
            for (int i = 0; i < this.pksOrders.size(); ++i) {
                String pk = this.pksOrders.get(i);
                int index = this.fieldOrders.indexOf(pk);
                nr[i] = r[index];
            }

            datas.add(nr);
        }
        batchDelete(BatchValueConverter.convert(datas, pksOrders.size(), batchLimits), this.deleteStatementSql,
                this.deleteArgsType);
        return records.size();

		/*TransactionStatus status = transactionManager.getTransaction(getTransactionDefinition());
		if (log.isDebugEnabled()) {
			log.debug("Execute Delete SQL : {}", this.deleteStatementSql);
		}

		try {
			int[] affects = jdbcTemplate.batchUpdate(this.deleteStatementSql, datas, this.deleteArgsType);

			transactionManager.commit(status);
			return affects.length;
		} catch (Exception e) {
			transactionManager.rollback(status);
			throw e;
		} finally {
			datas.clear();
		}*/
    }

    /**
     * 与全量写入共用单批 JDBC 事务边界；执行/提交未知时不自动逐行重投，
     * 不通过恢复 autoCommit 隐式提交残留事务，也不把原始行和驱动错误写入日志。
     */
    protected void batchOperator(List<Object[]> recordValues, String batchSql, int[] argTypes) {
        batchLimits.requireStatementParameters(argTypes.length);
        JdbcBatchExecutor.execute(dataSource, batchSql, recordValues, argTypes, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 批量插入（默认复用通用批处理实现，子类可覆盖以做方言优化）
     */
    protected void batchInsert(List<Object[]> recordValues, String batchSql, int[] argTypes) {
        batchOperator(recordValues, batchSql, argTypes);
    }

    /**
     * 批量删除（默认复用通用批处理实现，子类可覆盖）
     */
    protected void batchDelete(List<Object[]> recordValues, String batchSql, int[] argTypes) {
        batchOperator(recordValues, batchSql, argTypes);
    }

    /**
     * 批量更新（默认复用通用批处理实现，子类可覆盖）
     */
    protected void batchUpdate(List<Object[]> recordValues, String batchSql, int[] argTypes) {
        batchOperator(recordValues, batchSql, argTypes);
    }

}
