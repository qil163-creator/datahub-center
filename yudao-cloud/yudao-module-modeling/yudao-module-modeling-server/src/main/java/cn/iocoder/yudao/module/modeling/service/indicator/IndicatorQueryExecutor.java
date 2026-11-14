package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;
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
        return execute(connection, plans, List.of(), maxRows);
    }

    /**
     * 在同一连接上依次执行计划；需要原生 catalog 身份的产品（Doris）在每条计划执行前后复验同一对象身份。
     *
     * @param connection 已解析的连接信息
     * @param plans      聚合计划
     * @param relations  与计划一一对应的原生关系身份；不需要身份的产品传空列表
     * @param maxRows    每条语句最大行数
     * @return 与计划一一对应的结果行（别名 → 原始值）
     */
    public List<List<Map<String, Object>>> execute(ModelingDatabaseResolver.ConnectionInfo connection,
            List<AggregateQuerySql.Plan> plans, List<CatalogRelationIdentity> relations, int maxRows) {
        boolean catalog = AggregateQuerySql.requiresCatalogRelation(connection.getProductType());
        if (catalog && relations.size() != plans.size()) {
            throw new IllegalArgumentException("每条计划都必须携带原生关系身份");
        }
        try (Connection jdbc = open(connection)) {
            if (!catalog) {
                jdbc.setAutoCommit(false);
            }
            setReadOnlyQuietly(jdbc);
            try {
                List<List<Map<String, Object>>> results = new ArrayList<>();
                for (int index = 0; index < plans.size(); index++) {
                    if (catalog) {
                        requireCurrent(jdbc, connection, relations.get(index));
                    }
                    results.add(run(jdbc, plans.get(index), maxRows));
                    if (catalog) {
                        // 结果只在执行后复验同一对象身份才交给调用方，避免表被重建后返回混杂数据。
                        requireCurrent(jdbc, connection, relations.get(index));
                    }
                }
                return results;
            } finally {
                if (!catalog) {
                    rollbackQuietly(jdbc);
                }
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
     * 在只读连接上观测目标表的原生 catalog 身份，供需要身份绑定的产品渲染聚合计划。
     *
     * @param connection 已解析的连接信息
     * @param targets    目标表，元素为 {schema, table}
     * @return 与目标一一对应的原生关系身份
     */
    public List<CatalogRelationIdentity> resolveRelations(ModelingDatabaseResolver.ConnectionInfo connection,
            List<String[]> targets) {
        try (Connection jdbc = open(connection)) {
            setReadOnlyQuietly(jdbc);
            List<CatalogRelationIdentity> relations = new ArrayList<>();
            for (String[] target : targets) {
                relations.add(resolve(jdbc, connection, target[0], target[1], null));
            }
            return relations;
        } catch (SQLException failed) {
            log.warn("[resolveRelations][指标来源关系身份读取失败 datasource={} sqlState={} code={}]",
                    connection.getDatabaseId(), failed.getSQLState(), failed.getErrorCode());
            throw exception(INDICATOR_QUERY_FAILED);
        }
    }

    /**
     * 打开只读查询连接；Doris 使用产品参数传输契约，保证服务端绑定。
     */
    private Connection open(ModelingDatabaseResolver.ConnectionInfo connection) throws SQLException {
        String jdbcUrl = AggregateQuerySql.requiresCatalogRelation(connection.getProductType())
                ? dbSwitchRuntime.products().metadata(connection.getProductType())
                .preparePageQueryJdbcUrl(connection.getJdbcUrl())
                : connection.getJdbcUrl();
        return dbSwitchRuntime.openConnection(connection.getProductType(), null, jdbcUrl,
                connection.getUsername(), connection.getPassword());
    }

    /**
     * 复验执行连接仍指向观测阶段确认的同一原生对象。
     */
    private void requireCurrent(Connection jdbc, ModelingDatabaseResolver.ConnectionInfo connection,
            CatalogRelationIdentity expected) throws SQLException {
        resolve(jdbc, connection, expected.schemaName(), expected.tableName(), expected.objectId());
    }

    /**
     * 观测原生关系身份；名称不一致、对象身份变化或产品不符时拒绝执行。
     */
    private CatalogRelationIdentity resolve(Connection jdbc, ModelingDatabaseResolver.ConnectionInfo connection,
            String schema, String table, String expectedObjectId) throws SQLException {
        CatalogRelationIdentity relation = dbSwitchRuntime.products().metadata(connection.getProductType())
                .resolveCatalogRelation(jdbc, schema, table, QUERY_TIMEOUT_SECONDS);
        if (relation == null || relation.product() != connection.getProductType()
                || !Objects.equals(schema, relation.schemaName()) || !Objects.equals(table, relation.tableName())
                || expectedObjectId != null && !expectedObjectId.equals(relation.objectId())) {
            log.warn("[resolve][指标来源原生关系身份变化或超出已验证范围 datasource={}]", connection.getDatabaseId());
            throw exception(INDICATOR_QUERY_FAILED);
        }
        return relation;
    }

    /**
     * 部分驱动不支持切换只读模式；查询语句本身由组件限定为单表 SELECT。
     */
    private void setReadOnlyQuietly(Connection jdbc) {
        try {
            jdbc.setReadOnly(true);
        } catch (SQLException ignored) {
            // 只读模式不可用时仍只执行组件渲染的受限 SELECT。
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
