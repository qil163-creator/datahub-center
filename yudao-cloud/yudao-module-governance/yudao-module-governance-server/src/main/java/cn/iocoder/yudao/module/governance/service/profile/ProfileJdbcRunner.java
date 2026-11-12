package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileAggregatePlan;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileColumn;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileColumnStats;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileRequest;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileTopValuesQuery;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在目标库上执行探查 SQL（只读、事务外、每条查询 60 秒超时）。SQL 由 biz-quality {@link ProfileSqlGenerator} 生成，
 * 本类只负责建连、执行顺序、超时/行数上限、心跳回调与资源关闭。
 *
 * <p>执行顺序：① 连接安全校验 → ② 全表 COUNT(*)（失败或超时记为未知，不中断）→ ③ 按每批 100 列执行单遍聚合
 * → ④ 逐列 Top-N（单列失败只跳过该列分布）。每条查询结束后调用心跳续租；心跳抛出异常即中止。</p>
 *
 * @author Li
 */
@Slf4j
@Component
public class ProfileJdbcRunner {

    /** 单条查询超时（秒） */
    public static final int QUERY_TIMEOUT_SECONDS = 60;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    /**
     * 原始探查结果（尚未脱敏，禁止直接落库或记录日志）。
     *
     * @param totalRows   全表总行数；失败时为 null
     * @param sampledRows 抽样行数
     * @param stats       按列名的聚合统计
     * @param topValues   按列名的 Top 值
     */
    public record RawProfile(Long totalRows, long sampledRows, Map<String, ProfileColumnStats> stats,
                             Map<String, List<ProfileTopValuesQuery.ValueCount>> topValues) {

    }

    /**
     * 执行一次完整探查。
     *
     * @param conn       已解析的连接信息
     * @param schemaName 物理 schema，可为空
     * @param tableName  物理表名
     * @param columns    待探查列（任意数量，内部按 {@link ProfileRequest#MAX_COLUMNS} 分批）
     * @param sampleRows 抽样行数
     * @param topN       每列 Top 值数量
     * @param heartbeat  每条查询完成后调用（续租）；抛出异常时中止并向上传播
     */
    public RawProfile collect(MasterDatabaseResolver.ConnectionInfo conn, String schemaName, String tableName,
            List<ProfileColumn> columns, int sampleRows, int topN, Runnable heartbeat) throws SQLException {
        ProfileSqlGenerator generator = new ProfileSqlGenerator(dbSwitchRuntime.products());
        List<ProfileRequest> batches = new ArrayList<>();
        for (int from = 0; from < columns.size(); from += ProfileRequest.MAX_COLUMNS) {
            batches.add(new ProfileRequest(conn.getProductType(), schemaName, tableName,
                    columns.subList(from, Math.min(columns.size(), from + ProfileRequest.MAX_COLUMNS)),
                    sampleRows, topN));
        }
        try (var dataSource = databaseResolver.createDataSource(conn);
             Connection connection = dataSource.getConnection()) {
            AuditSqlDialect.validateConnection(connection, conn.getProductType());
            trySetReadOnly(connection);
            Long totalRows = queryTotal(connection, generator.buildTotalCountSql(batches.get(0)));
            heartbeat.run();
            long sampled = 0;
            Map<String, ProfileColumnStats> stats = new HashMap<>();
            for (ProfileRequest batch : batches) {
                ProfileAggregatePlan plan = generator.buildAggregate(batch);
                ProfileAggregatePlan.ProfileAggregateResult result = queryAggregate(connection, plan);
                sampled = Math.max(sampled, result.sampledRows());
                result.columns().forEach(column -> stats.put(column.column().name(), column));
                heartbeat.run();
            }
            Map<String, List<ProfileTopValuesQuery.ValueCount>> top = new HashMap<>();
            for (ProfileRequest batch : batches) {
                for (ProfileTopValuesQuery query : generator.buildTopValues(batch)) {
                    try {
                        top.put(query.column().name(), queryTop(connection, query));
                    } catch (SQLException ex) {
                        // 单列分布失败（类型不可分组等）不影响其他指标；不记录 SQL 与取值
                        log.warn("[collect][表({}) 字段 Top 值查询失败，跳过] SQLState={}", tableName, ex.getSQLState());
                    }
                    heartbeat.run();
                }
            }
            return new RawProfile(totalRows, sampled, stats, top);
        }
    }

    /**
     * 全表总行数；超时或失败返回 null，不中断探查。
     */
    private Long queryTotal(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(1);
            try (ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException ex) {
            log.warn("[queryTotal][全表计数失败，记为未知] SQLState={}", ex.getSQLState());
            return null;
        }
    }

    /**
     * 执行单遍聚合，结果恰为一行。
     */
    private ProfileAggregatePlan.ProfileAggregateResult queryAggregate(Connection connection,
            ProfileAggregatePlan plan) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(1);
            try (ResultSet rs = statement.executeQuery(plan.sql())) {
                if (!rs.next()) {
                    throw new SQLException("Profile aggregate returned no row");
                }
                return plan.read(rs);
            }
        }
    }

    /**
     * 执行单列 Top-N。
     */
    private List<ProfileTopValuesQuery.ValueCount> queryTop(Connection connection, ProfileTopValuesQuery query)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(query.topN());
            try (ResultSet rs = statement.executeQuery(query.sql())) {
                return query.read(rs);
            }
        }
    }

    /**
     * 尽量把连接设为只读；驱动不支持时忽略（生成的 SQL 本身只读）。
     */
    private static void trySetReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException | UnsupportedOperationException ignored) {
            // Hive 等驱动不支持只读标记
        }
    }

}
