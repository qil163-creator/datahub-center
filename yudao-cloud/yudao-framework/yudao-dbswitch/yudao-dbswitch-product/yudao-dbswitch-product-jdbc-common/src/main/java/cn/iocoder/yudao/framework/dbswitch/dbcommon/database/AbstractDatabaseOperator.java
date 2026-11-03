package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.constant.Constants;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.PrepareStatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

/**
 * 数据读取抽象基类
 *
 * @author jrl
 */
@Slf4j
public abstract class AbstractDatabaseOperator implements IDatabaseOperator {

    protected cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions runtimeOptions =
            cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions.defaults();

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    @Override
    public void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        runtimeOptions = java.util.Objects.requireNonNull(options, "options");
    }


    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP_FORMAT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return simpleDateFormat;
    });


    protected DataSource dataSource;

    protected int fetchSize;

    /**
     * 校验构造参数并初始化 {@code AbstractDatabaseOperator}。
     */
    public AbstractDatabaseOperator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "数据源非法,为null");
        this.fetchSize = Constants.DEFAULT_FETCH_SIZE;
    }

    /**
     * 获取数据源。
     */
    @Override
    public DataSource getDataSource() {
        return this.dataSource;
    }

    /**
     * 获取读取(fetch)数据的批次大小。
     */
    @Override
    public int getFetchSize() {
        return this.fetchSize;
    }


    /**
     * 按增量边界读取表中的新增或变更数据。
     */
    @Override
    public PrepareStatementResultSet queryIncreaseTableData(String sourceSchemaName, String sourceTableName,
            List<String> queryFieldColumn, ColumnDescription increaseColumn, String startVal, String endVal) {
        return getPrepareStatementResultSet(new StringBuilder(
                String.format("select %s%s%s from %s%s%s.%s%s%s where 1=1", getDatabaseType().getQuote(),
                        StringUtils.join(queryFieldColumn,
                                getDatabaseType().getQuote() + "," + getDatabaseType().getQuote()),
                        getDatabaseType().getQuote(), getDatabaseType().getQuote(), sourceSchemaName,
                        getDatabaseType().getQuote(), getDatabaseType().getQuote(), sourceTableName,
                        getDatabaseType().getQuote())), increaseColumn, startVal, endVal);
    }

    /**
     * 按增量边界读取表中的新增或变更数据。
     */
    @Override
    public PrepareStatementResultSet queryIncreaseTableData(String sourceSql, List<String> queryFieldColumn,
            ColumnDescription increaseColumn, String startVal, String endVal) {
        return getPrepareStatementResultSet(new StringBuilder(
                String.format("select %s%s%s from (%s) t where 1=1", getDatabaseType().getQuote(),
                        StringUtils.join(queryFieldColumn,
                                getDatabaseType().getQuote() + "," + getDatabaseType().getQuote()),
                        getDatabaseType().getQuote(), sourceSql)), increaseColumn, startVal, endVal);
    }

    /**
     * 按增量列类型绑定查询边界并返回预编译查询结果集。
     */
    @SneakyThrows
    private PrepareStatementResultSet getPrepareStatementResultSet(StringBuilder sql, ColumnDescription increaseColumn,
            String startVal, String endVal) {
        int fieldType = increaseColumn.getFieldType();
        Object start;
        Object end;
        switch (fieldType) {
            case java.sql.Types.BIGINT:
            case java.sql.Types.INTEGER:
                start = startVal != null ? Long.parseLong(startVal) : null;
                end = endVal != null ? Long.parseLong(endVal) : null;
                break;
            case java.sql.Types.TIMESTAMP:
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
            case java.sql.Types.DATE:
                start = startVal != null ? new Timestamp(TIMESTAMP_FORMAT.get().parse(startVal).getTime()) : null;
                end = endVal != null ? new Timestamp(TIMESTAMP_FORMAT.get().parse(endVal).getTime()) : null;
                break;
            default:
                start = startVal;
                end = endVal;
        }
        List<Object> params = new ArrayList<>();
        if (start != null) {
            sql.append(String.format(" and %s%s%s>?", getDatabaseType().getQuote(), increaseColumn.getFieldName(),
                    getDatabaseType().getQuote()));
            params.add(start);
        }
        if (end != null) {
            sql.append(String.format(" and %s%s%s<=?", getDatabaseType().getQuote(), increaseColumn.getFieldName(),
                    getDatabaseType().getQuote()));
            params.add(end);
        }
        return this.selectTableData(sql.toString(), this.fetchSize, params);
    }

    /**
     * 返回当前字段是否属于主键。
     */
    @Override
    public Boolean getPkExist(String targetSchemaName, String targetTableName,
            Map<String, Object> pkVal) throws SQLException {
        if (CollectionUtils.isEmpty(pkVal)) {
            return false;
        }
        StringBuilder sql = new StringBuilder(
                String.format("select 1 from %s%s%s.%s%s%s where 1=1", getDatabaseType().getQuote(), targetSchemaName,
                        getDatabaseType().getQuote(), getDatabaseType().getQuote(), targetTableName,
                        getDatabaseType().getQuote()));
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : pkVal.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            sql.append(String.format(" AND %s%s%s=?", getDatabaseType().getQuote(), key, getDatabaseType().getQuote()));
            params.add(value);
        }
        try (PrepareStatementResultSet resultSet = this.selectTableData(sql.toString(), Constants.DEFAULT_FETCH_SIZE,
                params);
             ResultSet resultset = resultSet.getResultset()) {
            return resultset.next();
        }
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql) {
        return this.selectTableData(sql, this.fetchSize);
    }

    /**
     * 设置读取(fetch)数据的批次大小。
     */
    @Override
    public void setFetchSize(int size) {
		/*if (size < Constants.MINIMUM_FETCH_SIZE) {
			throw new IllegalArgumentException(
					"设置的批量处理行数的大小fetchSize不得小于" + Constants.MINIMUM_FETCH_SIZE);
		}*/

        this.fetchSize = size;
    }

    /**
     * 按数据库产品转换 fetchSize（对齐 dbswitch 2.0.2 的 ProductFeatures.convertFetchSize 设计）：
     * MySQL 协议族（MySQL/MariaDB/GBase8a/TiDB/Doris/StarRocks）需要设置为
     * Integer.MIN_VALUE 才能启用逐行流式读取，否则驱动会把整个结果集加载进内存，
     * 大表读取存在 OOM 风险；其余数据库原样返回
     */
    protected int convertFetchSize(int fetchSize) {
        ProductTypeEnum type = getDatabaseType();
        if (type != null && type.isLikeMysql()) {
            return Integer.MIN_VALUE;
        }
        return fetchSize;
    }

    /**
     * 已经指定的查询SQL语句查询数据结果集
     *
     * @param sql       查询的SQL语句
     * @param fetchSize 批处理大小
     * @return 结果集包装对象
     */
    protected final StatementResultSet selectTableData(String sql, int fetchSize) {
        if (log.isDebugEnabled()) {
            log.debug("Query table data sql :{}", sql);
        }

        StatementResultSet srs = new StatementResultSet();
        try {
            srs.setConnection(dataSource.getConnection());
            srs.setAutoCommit(srs.getConnection().getAutoCommit());
            srs.getConnection().setAutoCommit(false);
            srs.setStatement(srs.getConnection()
                    .createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
            srs.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            // 历史实现此路径未做产品转换，MySQL 系大表全量读取会整表进内存
            srs.getStatement().setFetchSize(convertFetchSize(fetchSize));
            srs.setResultset(srs.getStatement().executeQuery(sql));
            return srs;
        } catch (Throwable t) {
            srs.close();
            throw new RuntimeException(t);
        }
    }

    /**
     * 已经指定的查询SQL语句查询数据结果集
     *
     * @param sql       查询的SQL语句
     * @param fetchSize 批处理大小
     * @return 结果集包装对象
     */
    private PrepareStatementResultSet selectTableData(String sql, int fetchSize, List<Object> params) {
        if (log.isDebugEnabled()) {
            log.debug("Query table data sql :{}", sql);
        }

        PrepareStatementResultSet srs = new PrepareStatementResultSet();
        try {
            srs.setConnection(dataSource.getConnection());
            srs.setAutoCommit(srs.getConnection().getAutoCommit());
            srs.getConnection().setAutoCommit(false);
            PreparedStatement preparedStatement = srs.getConnection()
                    .prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            for (int i = 0; i < params.size(); i++) {
                preparedStatement.setObject(i + 1, params.get(i));
            }
            srs.setStatement(preparedStatement);
            srs.getStatement().setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            srs.getStatement().setFetchSize(convertFetchSize(fetchSize));
            srs.setResultset(srs.getStatement().executeQuery());
            return srs;
        } catch (Throwable t) {
            srs.close();
            throw new RuntimeException(t);
        }
    }

    /**
     * 执行写SQL操作
     *
     * @param sql 写SQL语句
     */
    protected final int executeSql(String sql) {
        if (log.isDebugEnabled()) {
            log.debug("Execute sql :{}", sql);
        }
        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement()) {
            return st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}
