package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.framework.dbswitch.core.service.AggregateQuerySql;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.modeling.service.model.ModelingDatabaseResolver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_QUERY_FAILED;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_QUERY_TIMEOUT;

/**
 * 在目标数据源上只读执行已渲染的聚合计划。
 * <p>
 * 连接由 DB Switch 运行时按 {@link ModelingDatabaseResolver} 解析的连接信息现开现关；不参与平台 Spring 事务。
 * 目标端使用只读、非自动提交会话（PostgreSQL JDBC 仅在非自动提交时下发 READ ONLY），结束时一律回滚。
 * 每条语句设置 30 秒查询超时和最大行数。
 */
@Slf4j
@Component
public class IndicatorQueryExecutor {

    /** 单条语句查询超时（秒）。 */
    public static final int QUERY_TIMEOUT_SECONDS = 30;

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    /**
     * 在同一连接上依次执行计划。
     *
     * @param connection 已解析的连接信息
     * @param plans      聚合计划
     * @param maxRows    每条语句最大行数
     * @return 与计划一一对应的结果行（别名 → 原始值）
     */
    public List<List<Map<String, Object>>> execute(ModelingDatabaseResolver.ConnectionInfo connection,
            List<AggregateQuerySql.Plan> plans, int maxRows) {
        try (Connection jdbc = dbSwitchRuntime.openConnection(connection.getProductType(), null,
                connection.getJdbcUrl(), connection.getUsername(), connection.getPassword())) {
            jdbc.setAutoCommit(false);
            jdbc.setReadOnly(true);
            try {
                List<List<Map<String, Object>>> results = new ArrayList<>();
                for (AggregateQuerySql.Plan plan : plans) {
                    results.add(run(jdbc, plan, maxRows));
                }
                return results;
            } finally {
                rollbackQuietly(jdbc);
            }
        } catch (SQLTimeoutException timeout) {
            log.warn("[execute][指标查询超时 datasource={} sqlState={}]", connection.getDatabaseId(),
                    timeout.getSQLState());
            throw exception(INDICATOR_QUERY_TIMEOUT);
        } catch (SQLException failed) {
            if ("57014".equals(failed.getSQLState())) {
                // PostgreSQL 取消语句（statement_timeout / setQueryTimeout）。
                throw exception(INDICATOR_QUERY_TIMEOUT);
            }
            // 不记录绑定值，只记录数据源与错误状态。
            log.warn("[execute][指标查询失败 datasource={} sqlState={} code={}]", connection.getDatabaseId(),
                    failed.getSQLState(), failed.getErrorCode());
            throw exception(INDICATOR_QUERY_FAILED);
        }
    }

    /**
     * 执行单条计划。
     */
    private List<Map<String, Object>> run(Connection jdbc, AggregateQuerySql.Plan plan, int maxRows)
            throws SQLException {
        try (PreparedStatement statement = jdbc.prepareStatement(plan.sql())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(maxRows);
            for (int index = 0; index < plan.parameters().size(); index++) {
                statement.setObject(index + 1, plan.parameters().get(index));
            }
            List<String> aliases = new ArrayList<>(plan.dimensionAliases());
            if (plan.timeAlias() != null) {
                aliases.add(plan.timeAlias());
            }
            aliases.add(plan.valueAlias());
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next() && rows.size() < maxRows) {
                    Map<String, Object> row = new HashMap<>();
                    for (String alias : aliases) {
                        row.put(alias, result.getObject(alias));
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    /**
     * 只读会话结束时回滚。
     */
    private void rollbackQuietly(Connection jdbc) {
        try {
            jdbc.rollback();
        } catch (SQLException ignored) {
            // 连接随后关闭，回滚失败不影响只读结果。
        }
    }
}
