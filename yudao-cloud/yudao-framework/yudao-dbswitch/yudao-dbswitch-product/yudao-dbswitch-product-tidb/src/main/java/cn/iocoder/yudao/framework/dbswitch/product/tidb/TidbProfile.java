package cn.iocoder.yudao.framework.dbswitch.product.tidb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.TidbRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlCompatibleProfile;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 共享 MySQL 协议读取器、游标目录凭据及原生 UPSERT 所使用的 TiDB 事实。 */
public final class TidbProfile extends MysqlCompatibleProfile {

    public static final TidbProfile INSTANCE = new TidbProfile();

    /**
     * 初始化 {@code TidbProfile}及其父类状态。
     */
    private TidbProfile() {
        super(ProductTypeEnum.TIDB, "TiDB");
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    public void verifyProduct(Connection connection, int timeout) throws SQLException {
        TidbRelationIdentity.verifyProduct(connection, timeout);
    }

    /**
     * 读取当前表的原生物理身份。
     */
    @Override
    public String physicalIdentity(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        return TidbRelationIdentity.cursorFingerprint(connection, schema, table, timeout);
    }

    /**
     * 绑定到游标键契约中的额外键比较事实；null 表示不添加。
     */
    @Override
    public String keyComparisonProfile(Connection connection, int timeout) throws SQLException {
        return TidbRelationIdentity.keyComparisonProfile(connection, timeout);
    }

    /**
     * 在更新或插入锁定前执行已配置的检查回调。
     */
    @Override
    public void beforeUpsertLock(Connection connection, int timeout) throws SQLException {
        TidbRelationIdentity.beginOwnedSnapshot(connection, timeout);
    }

    /** TiDB TIMESTAMP 表示时间线上的时刻；Connector/J 的 LOCAL 时区不得重新解释游标的 UTC 值。 */
    @Override
    public void executeUpsertBatch(JdbcBatchExecutor.WriteContext context, String sql, List<Object[]> rows,
            int[] types, int timeout) throws SQLException {
        String previousZone;
        try (var statement = context.connection().createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var result = statement.executeQuery("SELECT @@session.time_zone")) {
                if (!result.next() || (previousZone = result.getString(1)) == null || result.next()) {
                    throw new SQLException("TiDB target time zone is unavailable");
                }
            }
        }
        try {
            setTimeZone(context.connection(), "+00:00", timeout);
            var utcRows = new ArrayList<Object[]>(rows.size());
            for (var row : rows) {
                var copy = row.clone();
                for (int i = 0; i < copy.length; i++) {
                    if (types[i] == Types.TIMESTAMP && copy[i] instanceof OffsetDateTime instant) {
                        copy[i] = instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                    }
                }
                utcRows.add(copy);
            }
            JdbcBatchExecutor.executeBatch(context, sql, utcRows, types, timeout);
        } finally {
            // 恢复失败传递到 JdbcBatchExecutor 的回滚及丢弃流程，绝不复用连接池连接或报告成功。
            setTimeZone(context.connection(), previousZone, timeout);
        }
    }

    /**
     * 设置时间时区。
     */
    private static void setTimeZone(Connection connection, String zone, int timeout) throws SQLException {
        try (var statement = connection.prepareStatement("SET SESSION time_zone=?")) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, zone);
            statement.execute();
        }
    }

    /**
     * 在借用源端连接之前创建，可拒绝读取。
     */
    @Override
    public CursorReadHooks cursorReadHooks(DataSource source, CursorReadRequest request,
            int timeout) throws SQLException {
        if (request.sourceGenerationPin() == null
                || source instanceof HikariDataSource pool && pool.getMaximumPoolSize() < 2) {
            throw new SQLException(
                    "TiDB cursor requires a deployed generation and capacity for a snapshot plus a current-read connection");
        }
        return new SnapshotHooks(source, request.sourceGenerationPin(), timeout);
    }

    /** 在准入、自有快照内、每次分页前及释放时，固定部署代次。 */
    private static final class SnapshotHooks implements CursorReadHooks {

        private final DataSource source;

        private final SourceGenerationPin pin;

        private final int timeout;

        private String generation;

        private String snapshot;

        /**
         * 初始化 {@code SnapshotHooks}。
         */
        private SnapshotHooks(DataSource source, SourceGenerationPin pin, int timeout) {
            this.source = source;
            this.pin = pin;
            this.timeout = timeout;
        }

        /**
         * 在服务端产品验证后、会话修改前执行。
         */
        @Override
        public void afterProductVerified(Connection connection) throws SQLException {
            generation = TidbSourceGeneration.verifyCurrent(connection, pin, timeout);
        }

        /** TiDB 仅在启用无操作兼容开关时接受 READ ONLY，不得声称该提示得到强制执行。 */
        @Override
        public boolean readOnlyTransaction() {
            return false;
        }

        /**
         * 在 REPEATABLE READ 及手动提交生效后执行。
         */
        @Override
        public void afterTransactionStarted(Connection connection) throws SQLException {
            snapshot = TidbRelationIdentity.beginOwnedSnapshot(connection, timeout);
            if (!generation.equals(TidbSourceGeneration.verifySnapshot(connection, pin, snapshot, timeout))) {
                throw new SQLException("TiDB generation changed at snapshot admission");
            }
        }

        /**
         * 额外模式指纹材料；null 表示不添加。
         */
        @Override
        public String schemaMaterial() {
            return generation;
        }

        /**
         * 每次分页查询之前执行。
         */
        @Override
        public void beforePage(Connection connection) throws SQLException {
            TidbRelationIdentity.requireOwnedSnapshot(connection, snapshot, timeout);
            try (var current = source.getConnection()) {
                verifyGeneration(current);
            }
        }

        /**
         * 在事务释放后重新核对源端代次未发生变化。
         */
        @Override
        public void afterTransactionReleased(Connection connection) throws SQLException {
            if (generation != null) {
                verifyGeneration(connection);
            }
        }

        /**
         * 检查当前资源代次是否与预期代次一致。
         */
        private void verifyGeneration(Connection current) throws SQLException {
            if (!generation.equals(TidbSourceGeneration.verifyCurrent(current, pin, timeout))) {
                throw new SQLException("TiDB deployed generation or marker physical identity changed");
            }
        }
    }
}
