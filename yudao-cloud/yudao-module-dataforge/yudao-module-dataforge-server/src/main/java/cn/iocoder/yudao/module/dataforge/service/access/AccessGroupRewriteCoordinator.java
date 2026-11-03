package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.constant.ChangeDataSyncType;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.recovery.PostgresGroupRewriteStore;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessGroupRewriteProperties;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessGroupRewriteProperties.Deployment;
import cn.iocoder.yudao.module.dataforge.service.execution.RunLease;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_RUN_REVIEW_REQUIRED;

/** 仅负责平台准入、租约及固定记录；原生标识与目标端修改由 DB Switch 负责。
 * 每个平台事务均按任务 → 运行 → 固定记录的顺序加锁，且不调用目标数据库。 */
@Component
public class AccessGroupRewriteCoordinator {

    private final DataSource control;

    private final AccessGroupRewriteProperties properties;

    private final DbSwitchRuntime runtime;

    /**
     * 创建同步表组重写实例。
     *
     * @param control 控制
     * @param properties 组件配置
     * @param runtime 运行时
     */
    public AccessGroupRewriteCoordinator(DataSource control, AccessGroupRewriteProperties properties,
            DbSwitchRuntime runtime) {
        this.control = Objects.requireNonNull(control);
        this.properties = Objects.requireNonNull(properties);
        this.runtime = Objects.requireNonNull(runtime);
    }

    /**
     * 对启用的 TDengine 主键比较同步校验运行租约，构造表组重写句柄。
     *
     * @param access 访问
     * @param run 运行
     * @param lease 租约
     * @param source 来源数据
     * @param target 目标数据
     * @param sourceConnection 来源连接
     * @param targetConnection 目标连接
     * @return 句柄
     */
    public Handle prepare(DataAccessDO access, DataAccessTaskDO run, RunLease lease,
            SourceDataSourceProperties source, TargetDataSourceProperties target,
            AccessDatabaseResolver.ConnectionInfo sourceConnection,
            AccessDatabaseResolver.ConnectionInfo targetConnection) {
        if (!eligible(target) || !properties.isEnabled()) {
            return null;
        }
        if (run == null || lease == null || !Objects.equals(lease.recordId(), run.getId())
                || !Objects.equals(lease.token(), run.getExecutionToken())) {
            throw invalid();
        }
        return new Handle(
                context(access, run, lease.token(), source, target, sourceConnection, targetConnection, false));
    }

    /** 开销较大的文件及配置校验在调用方启动平台事务之前执行。 */
    public RecoveryAdmission prepareRecovery(DataAccessDO access, DataAccessTaskDO run, String recoveryToken,
            SourceDataSourceProperties source, TargetDataSourceProperties target,
            AccessDatabaseResolver.ConnectionInfo sourceConnection,
            AccessDatabaseResolver.ConnectionInfo targetConnection) {
        if (!eligible(target) || !properties.isEnabled()) {
            throw invalid();
        }
        return new RecoveryAdmission(
                context(access, run, recoveryToken, source, target, sourceConnection, targetConnection, true));
    }

    /**
     * 判断目标是否为启用主键有序比较增量同步的 TDengine 数据源。
     *
     * @param target 目标数据
     * @return 处理是否成功
     */
    private boolean eligible(TargetDataSourceProperties target) {
        return target != null && target.getTargetProductType() == ProductTypeEnum.TDENGINE && Boolean.TRUE.equals(
                target.getChangeDataSync())
                && ChangeDataSyncType.PK_ORDER_COMPARE.name().equals(target.getChangeDataSyncType());
    }

    /**
     * 校验固定驱动、发布快照、独占目标和无脚本配置，构造表组重写授权上下文。
     *
     * @param access 访问
     * @param run 运行
     * @param token 当前执行或租约令牌
     * @param source 来源数据
     * @param target 目标数据
     * @param sourceConnection 来源连接
     * @param targetConnection 目标连接
     * @param recovery 恢复
     * @return 上下文
     */
    private Context context(DataAccessDO access, DataAccessTaskDO run, String token,
            SourceDataSourceProperties source, TargetDataSourceProperties target,
            AccessDatabaseResolver.ConnectionInfo sourceConnection,
            AccessDatabaseResolver.ConnectionInfo targetConnection, boolean recovery) {
        if (access == null || run == null || source == null || !Objects.equals(run.getDataAccessId(), access.getId())
                || access.getId() == null || run.getConfigJson() == null || run.getReleaseRevision() == null
                || !Integer.valueOf(1).equals(source.getSourceType()) || Boolean.TRUE.equals(target.getTargetDrop())
                || Boolean.TRUE.equals(target.getTargetTruncateData()) || Boolean.TRUE.equals(target.getOnlyCreate())
                || text(source.getBeforeSqlScripts()) || text(source.getAfterSqlScripts())
                || text(target.getBeforeSqlScripts()) || text(target.getAfterSqlScripts())
                || target.getCustomDdlMap() != null && !target.getCustomDdlMap().isEmpty()) {
            throw invalid();
        }
        if (sourceConnection == null || targetConnection == null || sourceConnection.getDatabaseId() == null
                || targetConnection.getDatabaseId() == null || !text(sourceConnection.getConfigurationRevision())
                || !text(targetConnection.getConfigurationRevision())
                || !Objects.equals(access.getSourceDatabaseId(), sourceConnection.getDatabaseId())
                || !Objects.equals(access.getTargetDatabaseId(), targetConnection.getDatabaseId())
                || source.getSourceProductType() != sourceConnection.getProductType()
                || target.getTargetProductType() != targetConnection.getProductType()) {
            throw invalid();
        }
        GroupRewriteRecord.owner(token);
        var deployment = properties.authorize(access.getId());
        var sourceDriver = runtime.drivers().find(source.getSourceProductType())
                .orElseThrow(AccessGroupRewriteCoordinator::invalid);
        var targetDriver = runtime.drivers().find(target.getTargetProductType())
                .orElseThrow(AccessGroupRewriteCoordinator::invalid);
        sourceDriver.verifyUnchanged();
        targetDriver.verifyUnchanged();
        var limits = target.getBatchLimits() == null ? BatchLimits.defaults() : target.getBatchLimits();
        if (limits.maxBatchBytes() > GroupRewriteMaterial.MAX_BYTES || limits.maxBatchBytes() > runtime.options()
                .maxCacheBytes()) {
            throw invalid();
        }
        String frozen = hash(run.getConfigJson());
        String configuration = hash("ACCESS_GROUP_CONFIG_V1", frozen, run.getReleaseRevision().toString(),
                sourceConnection.getDatabaseId().toString(), targetConnection.getDatabaseId().toString(),
                sourceConnection.getConfigurationRevision(), targetConnection.getConfigurationRevision(),
                source.getSourceProductType().name(), target.getTargetProductType().name(), sourceDriver.profileId(),
                sourceDriver.fingerprint(),
                targetDriver.profileId(), targetDriver.fingerprint(), limits.toString());
        return new Context(access.getId(), targetConnection.getDatabaseId(), run.getId(), token,
                run.getReleaseRevision(), frozen, configuration, limits, deployment, recovery);
    }

    public static final class RecoveryAdmission {

        private final Context context;

        /**
         * 创建恢复准入实例。
         *
         * @param context 当前处理上下文
         */
        private RecoveryAdmission(Context context) {
            this.context = context;
        }

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessGroupRecoveryAdmission[redacted]";
        }
    }

    /** 调用方已在同一事务内锁定并更新运行的对账令牌；此处不进行目标端 I/O。 */
    public RecoveryHandle admitRecovery(Connection connection, RecoveryAdmission admission) {
        Objects.requireNonNull(admission);
        Context context = admission.context;
        try {
            requireTransaction(connection);
            lockOwner(connection, context, false);
            var identities = new LinkedHashMap<String, Identity>();
            for (String scope : taskScopes(connection, context)) {
                Pin pin = select(connection, context, scope);
                if (pin == null || pin.identity.task != context.task) {
                    throw unavailable();
                }
                // 归属者是可变的认证状态。在 SQL 中提前过滤，可能在校验状态 MAC 之前
                // 隐藏已损坏的待处理固定记录。
                if (pin.owner != context.run) {
                    continue;
                }
                if (!pin.occupied || !pin.drained || pin.identity.target != context.target || pin.token.equals(
                        context.token)) {
                    throw unavailable();
                }
                Identity identity = pin.identity;
                if (!identity.scope.equals(hash("ACCESS_GROUP_SCOPE_V1", Long.toString(context.task),
                        Long.toString(context.target), identity.schema, identity.table))
                        || !identity.configuration.equals(
                        hash(context.configuration, identity.schema, identity.table, identity.structure))) {
                    throw unavailable();
                }
                identities.put(scope, identity);
            }
            if (identities.isEmpty()) {
                throw unavailable();
            }
            // 修改任何归属者之前，先校验整个集合，即使调用方捕获了此处异常也不例外。
            for (Identity identity : identities.values()) {
                update(connection, context, select(connection, context, identity.scope), true, false);
            }
            return new RecoveryHandle(context, identities);
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    /** 已释放且已完全退出状态的 MAC，证明此前已完成整份认证日志的扫描。
     * 复用调用方事务；任务和运行记录持锁期间绝不打开第二个连接。 */
    public void assertResolved(Connection connection, long task, long run) {
        try {
            requireTransaction(connection);
            String schema = properties.getControlSchema();
            if (schema == null || !schema.matches("[a-z][a-z0-9_-]{0,62}")) {
                throw unavailable();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM \"" + schema + "\".\"dh_dataforge_access\" WHERE id=? AND deleted=0 FOR UPDATE")) {
                statement.setQueryTimeout(10);
                statement.setLong(1, task);
                try (var row = statement.executeQuery()) {
                    if (!row.next() || row.getLong(1) != task || row.next()) {
                        throw unavailable();
                    }
                }
            }
            try (var statement = connection.prepareStatement(
                    "SELECT data_access_id FROM \"" + schema + "\".\"dh_dataforge_access_task\" WHERE id=? AND deleted=0 FOR UPDATE")) {
                statement.setQueryTimeout(10);
                statement.setLong(1, run);
                try (var row = statement.executeQuery()) {
                    if (!row.next() || row.getLong(1) != task || row.next()) {
                        throw unavailable();
                    }
                }
            }
            // 即使部署已禁用，也必须执行已有固定记录的约束；非分组运行则不需要密钥。
            var scopes = new ArrayList<String>();
            try (var statement = connection.prepareStatement(
                    "SELECT scope FROM \"" + schema + "\".\"dh_dataforge_access_group_pin\""
                            + " WHERE data_access_id=? ORDER BY scope LIMIT 4097 FOR UPDATE")) {
                statement.setQueryTimeout(10);
                statement.setLong(1, task);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        scopes.add(rows.getString(1));
                    }
                }
            }
            if (scopes.isEmpty()) {
                return;
            }
            if (scopes.size() > 4096) {
                throw unavailable();
            }
            var deployment = properties.authorize(task);
            var context = new Context(task, 0, run, "", 0, "", "", BatchLimits.defaults(), deployment, false);
            for (String scope : scopes) {
                var pin = select(connection, context, scope);
                if (pin == null || pin.identity.task != task) {
                    throw unavailable();
                }
                if (pin.owner == run && (pin.occupied || !pin.drained)) {
                    throw unavailable();
                }
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    public record RecoveryStatus(boolean applicable, boolean ready, boolean released, String message) {

        /**
         * 创建恢复状态实例。
         *
         * @param applicable 适用
         * @param ready 就绪
         * @param message 消息
         */
        private RecoveryStatus(boolean applicable, boolean ready, String message) {
            this(applicable, ready, false, message);
        }
    }

    /** 只读提示，不构成执行授权；仅使用平台状态，命令准入时会再次校验。 */
    public RecoveryStatus recoveryStatus(Connection connection, long task, long run) {
        try {
            requireTransaction(connection);
            String schema = properties.getControlSchema();
            if (schema == null || !schema.matches("[a-z][a-z0-9_-]{0,62}")) {
                return new RecoveryStatus(true, false, "恢复配置无效，请联系管理员核对部署配置并保留任务占位。");
            }
            int status;
            boolean occupied, review, activeRecovery;
            try (var query = connection.prepareStatement(
                    "SELECT data_access_id,run_status,occupies_slot,review_required,"
                            + "reconcile_token IS NOT NULL AND reconcile_lease_until>clock_timestamp() FROM \"" + schema
                            + "\".\"dh_dataforge_access_task\" WHERE id=? AND deleted=0")) {
                query.setQueryTimeout(10);
                query.setLong(1, run);
                try (var row = query.executeQuery()) {
                    if (!row.next() || row.getLong(1) != task) {
                        throw unavailable();
                    }
                    status = row.getInt(2);
                    occupied = row.getBoolean(3);
                    review = row.getBoolean(4);
                    activeRecovery = row.getBoolean(5);
                    if (row.next()) {
                        throw unavailable();
                    }
                }
            }
            var scopes = new ArrayList<String>();
            try (var query = connection.prepareStatement(
                    "SELECT scope FROM \"" + schema + "\".\"dh_dataforge_access_group_pin\""
                            + " WHERE data_access_id=? ORDER BY scope LIMIT 4097")) {
                query.setQueryTimeout(10);
                query.setLong(1, task);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        scopes.add(rows.getString(1));
                    }
                }
            }
            if (scopes.isEmpty()) {
                return new RecoveryStatus(false, false, "此记录没有登记需要恢复的数据组。");
            }
            if (!properties.isEnabled()) {
                return new RecoveryStatus(true, false, "数据组恢复未启用，请联系管理员核对部署配置。");
            }
            Deployment deployment;
            try {
                deployment = properties.authorize(task);
            } catch (RuntimeException invalid) {
                return new RecoveryStatus(true, false, "部署独占授权或恢复密钥配置不可用，请联系管理员核对并保留占位。");
            }
            var context = new Context(task, 0, run, "", 0, "", "", BatchLimits.defaults(), deployment, false);
            boolean found = false, allReleased = true, allDrained = true;
            try {
                if (scopes.size() > 4096) {
                    throw unavailable();
                }
                for (String scope : scopes) {
                    var pin = select(connection, context, scope, false);
                    if (pin == null || pin.identity.task != task) {
                        throw unavailable();
                    }
                    if (pin.owner != run) {
                        continue; // 两个 MAC 均通过认证后才进行过滤。
                    }
                    found = true;
                    allReleased &= !pin.occupied && pin.drained;
                    allDrained &= pin.drained;
                }
            } catch (RuntimeException integrity) {
                return new RecoveryStatus(true, false, "恢复记录完整性校验未通过，请保留占位并核查平台恢复记录。");
            }
            if (!found) {
                return new RecoveryStatus(false, false, "此记录没有登记需要恢复的数据组。");
            }
            if (allReleased) {
                return new RecoveryStatus(true, false, true,
                        "已登记的数据组已释放；仍须核查原同步的完整输出并提交人工核查结论。");
            }
            if (activeRecovery) {
                return new RecoveryStatus(true, false, "恢复已受理，正在执行或核查退出状态；受理不代表恢复完成。");
            }
            if (!allDrained) {
                return new RecoveryStatus(true, false,
                        "执行资源尚未确认退出，请保留占位；停止确认或租约到期不能替代服务端退出证据。");
            }
            if (status != 5 || !occupied || !review) {
                return new RecoveryStatus(true, false, "当前记录不处于可恢复的待核查占位状态，请刷新记录。");
            }
            return new RecoveryStatus(true, true,
                    "服务端已确认上一执行器退出，可提交数据组恢复；执行前仍将复核权限、原配置和目标身份。");
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    /**
     * 确认控制库连接处于显式事务内，拒绝自动提交连接。
     *
     * @param connection 数据库连接
     */
    private void requireTransaction(Connection connection) throws SQLException {
        if (connection == null || connection.getAutoCommit()) {
            throw unavailable();
        }
    }

    /**
     * 按任务锁定并读取表组固定范围，拒绝超过处理上限的范围数量。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @return 按任务锁定并读取表组固定范围，拒绝超过处理上限的范围数量结果集合
     */
    private List<String> taskScopes(Connection connection, Context context) throws SQLException {
        var scopes = new ArrayList<String>();
        try (var statement = statement(connection, context,
                "SELECT scope FROM " + table(context, "dh_dataforge_access_group_pin")
                        + " WHERE data_access_id=? ORDER BY scope LIMIT 4097 FOR UPDATE")) {
            statement.setLong(1, context.task);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    scopes.add(rows.getString(1));
                }
            }
        }
        if (scopes.size() > 4096) {
            throw unavailable();
        }
        return scopes;
    }

    /** 仅存在于运行时的句柄；返回 false 时，调用方必须保持运行处于 UNKNOWN 状态并继续占用槽位。 */
    public class Handle implements GroupRewriteBindingProvider {

        private final Context context;

        private final Map<String, GroupRewriteBinding> bindings = new LinkedHashMap<>();

        private final Map<String, Identity> identities = new LinkedHashMap<>();

        private volatile boolean finishing;

        private volatile boolean confirmedExecutorDrain;

        private boolean admissionUnconfirmed;

        private boolean released;

        /**
         * 创建句柄实例。
         *
         * @param context 当前处理上下文
         */
        private Handle(Context context) {
            this.context = context;
        }

        /**
         * 判断启用状态条件是否成立。
         *
         * @return 处理是否成功
         */
        public boolean enabled() {
            return context != null;
        }

        /** 仅当所有保留固定记录的实际退出凭据均已持久确认时才返回 true。 */
        public boolean hasConfirmedExecutorDrain() {
            return confirmedExecutorDrain;
        }

        /**
         * 解析句柄配置或身份。
         *
         * @param schema 结构
         * @param table 表
         * @param logical 逻辑
         * @param physical 物理
         * @param structure 结构
         * @return 表组重写绑定
         */
        @Override
        public synchronized GroupRewriteBinding resolve(String schema, String table, String logical, String physical,
                String structure) {
            if (context == null || finishing || identities.size() >= 4096) {
                throw invalid();
            }
            route(schema);
            route(table);
            fingerprint(logical);
            fingerprint(physical);
            fingerprint(structure);
            String scope = hash("ACCESS_GROUP_SCOPE_V1", Long.toString(context.task),
                    Long.toString(context.target), schema, table);
            var identity = new Identity(scope, context.task, context.target, schema, table, logical, physical,
                    structure,
                    hash(context.configuration, schema, table, structure));
            if (identities.containsKey(scope)) {
                if (!identities.get(scope).equals(identity)) {
                    throw invalid();
                }
                return bindings.get(scope);
            }
            try {
                transaction(context, connection -> {
                    lockOwner(connection, context, false);
                    admit(connection, context, identity);
                    return null;
                });
                return addBinding(identity);
            } catch (RuntimeException failure) {
                admissionUnconfirmed = true;
                throw failure;
            }
        }

        /**
         * 添加绑定。
         *
         * @param identity 身份
         * @return 表组重写绑定
         */
        private GroupRewriteBinding addBinding(Identity identity) {
            var binding = new GroupRewriteBinding(identity.scope, identity.configuration, identity.physical,
                    context.token,
                    store(context, identity, false), context.limits, true,
                    context.recovery ? GroupRewriteBinding.ResumeMode.RECONCILE_AFTER_PRIOR_EXECUTOR_EXIT : GroupRewriteBinding.ResumeMode.NEW_OPERATION_ONLY,
                    () -> {
                        if (finishing) {
                            throw unavailable();
                        }
                        transaction(context, connection -> {
                            guard(connection, context, identity, null, false);
                            return null;
                        });
                    });
            identities.put(identity.scope, identity);
            bindings.put(identity.scope, binding);
            return binding;
        }

        /**
         * 判断已完成条件是否成立。
         *
         * @param terminationConfirmed 终止已确认
         * @return 处理是否成功
         */
        public synchronized boolean finished(boolean terminationConfirmed) {
            finishing = true;
            if (released) {
                return true;
            }
            if (!terminationConfirmed || admissionUnconfirmed) {
                return false;
            }
            if (context == null) {
                confirmedExecutorDrain = true;
                released = true;
                return true;
            }
            try {
                for (Identity identity : identities.values()) {
                    transaction(context, connection -> {
                        var pin = guard(connection, context, identity, null, true);
                        if (!pin.drained) {
                            update(connection, context, pin, true, true);
                        }
                        return null;
                    });
                }
                confirmedExecutorDrain = true;
                // 完全退出确认覆盖整个执行器。首张表仍待处理时，不得使后续表
                // 缺少退出凭据，否则显式恢复将永久无法执行。
                for (Identity identity : identities.values()) {
                    // 使用独立的完成确认存储：普通绑定拒绝已完全退出的归属者。包括 APPLIED 在内的
                    // 所有头记录均须由组件完成认证后，才可释放占用。
                    var completion = store(context, identity, true);
                    String after = null;
                    do {
                        var page = completion.pendingKeys(identity.scope, identity.physical, after, 256);
                        if (!page.keys().isEmpty()) {
                            return false;
                        }
                        after = page.nextAfter();
                    } while (after != null);
                }
                // 若其他表仍有待处理或未认证的工作，不得仅释放部分记录。
                transaction(context, connection -> {
                    lockOwner(connection, context, true);
                    for (Identity identity : identities.values()) {
                        var pin = requirePin(connection, context, identity, true);
                        if (!pin.drained) {
                            throw unavailable();
                        }
                        update(connection, context, pin, false, true);
                    }
                    return null;
                });
                released = true;
                return true;
            } catch (RuntimeException unknown) {
                return false;
            }
        }

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessGroupRewriteHandle[enabled=" + enabled() + "]";
        }
    }

    public record RecoveryTable(String schema, String table, List<String> fields, List<String> primaryKeys,
                                GroupRewriteBinding binding) {

        /**
         * 创建恢复表实例。
         *
         * @param schema 结构
         * @param table 表
         * @param fields 字段定义
         * @param primaryKeys 主键
         * @param binding 绑定
         */
        public RecoveryTable {
            fields = List.copyOf(fields);
            primaryKeys = List.copyOf(primaryKeys);
            Objects.requireNonNull(binding);
        }

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessGroupRecoveryTable[redacted]";
        }
    }

    public final class RecoveryHandle extends Handle {

        /**
         * 创建恢复句柄实例。
         *
         * @param context 当前处理上下文
         * @param identities 身份
         */
        private RecoveryHandle(Context context, Map<String, Identity> identities) {
            super(context);
            for (Identity identity : identities.values()) {
                super.addBinding(identity);
            }
        }

        /**
         * 拒绝继续使用已进入完成阶段的恢复句柄，并逐项复核绑定资源的执行归属。
         */
        public synchronized void assertOwnership() {
            if (super.finishing) {
                throw unavailable();
            }
            for (GroupRewriteBinding binding : super.bindings.values()) {
                binding.checkOwnership();
            }
        }

        /**
         * 解析恢复句柄配置或身份。
         *
         * @param schema 结构
         * @param table 表
         * @param logical 逻辑
         * @param physical 物理
         * @param structure 结构
         * @return 表组重写绑定
         */
        @Override
        public synchronized GroupRewriteBinding resolve(String schema, String table, String logical, String physical,
                String structure) {
            if (super.finishing) {
                throw unavailable();
            }
            for (Identity identity : super.identities.values()) {
                if (identity.schema.equals(schema) && identity.table.equals(table)) {
                    if (!identity.logical.equals(logical) || !identity.physical.equals(
                            physical) || !identity.structure.equals(structure)) {
                        throw unavailable();
                    }
                    var binding = super.bindings.get(identity.scope);
                    binding.checkOwnership();
                    return binding;
                }
            }
            throw unavailable();
        }

        /** 不读取源端数据；执行目标端操作前，认证每一页、每个头记录及所有待处理材料。 */
        public synchronized List<RecoveryTable> pendingTables() {
            if (super.finishing) {
                throw unavailable();
            }
            var tables = new ArrayList<RecoveryTable>();
            for (Identity identity : super.identities.values()) {
                var binding = super.bindings.get(identity.scope);
                binding.checkOwnership();
                List<String> fields = null, keys = null;
                String after = null;
                do {
                    var page = binding.store().pendingKeys(identity.scope, identity.physical, after, 256);
                    for (GroupRewriteKey key : page.keys()) {
                        var record = binding.store().read(key).orElseThrow(AccessGroupRewriteCoordinator::unavailable);
                        var material = record.material();
                        if (!material.bindingFingerprint()
                                .equals(identity.configuration) || !material.physicalFingerprint()
                                .equals(identity.physical)
                                || !material.structureFingerprint().equals(identity.structure)) {
                            throw unavailable();
                        }
                        if (fields == null) {
                            fields = material.fields();
                            keys = material.keys();
                        } else if (!fields.equals(material.fields()) || !keys.equals(material.keys())) {
                            throw unavailable();
                        }
                    }
                    after = page.nextAfter();
                } while (after != null);
                if (fields != null) {
                    tables.add(new RecoveryTable(identity.schema, identity.table, fields, keys, binding));
                }
            }
            return List.copyOf(tables);
        }
    }

    /**
     * 构造带固定范围、密钥和运行门禁的 PostgreSQL 表组恢复存储。
     *
     * @param context 当前处理上下文
     * @param identity 身份
     * @param completion 完成
     * @return PostgreSQL表组重写存储
     */
    private PostgresGroupRewriteStore store(Context context, Identity identity, boolean completion) {
        return new PostgresGroupRewriteStore(control,
                context.deployment.schema() + ".dh_dataforge_access_group_recovery", identity.scope,
                context.deployment.currentKeyId(), context.deployment.keys(), context.limits,
                context.deployment.timeout(),
                (connection, key) -> guard(connection, context, identity, key, completion));
    }

    /**
     * 锁定运行归属并核对逻辑与物理目标，校验当前表组占用及执行令牌。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @param identity 身份
     * @param key 标识键
     * @param completion 完成
     * @return 固定引用
     */
    private Pin guard(Connection connection, Context context, Identity identity, GroupRewriteKey key,
            boolean completion) throws SQLException {
        lockOwner(connection, context, completion);
        if (key != null && (!identity.scope.equals(key.scope()) || !identity.physical.equals(key.resource()))) {
            throw unavailable();
        }
        return requirePin(connection, context, identity, completion);
    }

    /**
     * 校验表组固定身份、执行令牌和占用状态，完成阶段额外排除冲突恢复记录。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @param identity 身份
     * @param completion 完成
     * @return 固定引用
     */
    private Pin requirePin(Connection connection, Context context, Identity identity,
            boolean completion) throws SQLException {
        var pin = select(connection, context, identity.scope);
        if (pin == null || !identity.equals(
                pin.identity) || !pin.occupied || pin.owner != context.run || !pin.token.equals(context.token)
                || !completion && pin.drained) {
            throw unavailable();
        }
        if (completion) {
            try (var statement = statement(connection, context,
                    "SELECT 1 FROM " + table(context, "dh_dataforge_access_group_recovery")
                            + " WHERE scope=? AND resource<>? LIMIT 1")) {
                statement.setString(1, identity.scope);
                statement.setString(2, identity.physical);
                try (var row = statement.executeQuery()) {
                    if (row.next()) {
                        throw unavailable();
                    }
                }
            }
        }
        return pin;
    }

    /**
     * 锁定并校验当前任务、运行、发布修订及执行令牌的归属关系。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @param completion 完成
     */
    private void lockOwner(Connection connection, Context context, boolean completion) throws SQLException {
        AccessRewriteRunGuard.lock(connection, new AccessRewriteRunGuard.Owner(context.task, context.run,
                context.token, context.recovery, context.releaseRevision, context.frozen, context.deployment.schema(),
                context.deployment.timeout()), completion);
    }

    /**
     * 核对或创建表组固定身份及占用记录，拒绝目标变化或另一运行持有的占用。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @param identity 身份
     */
    private void admit(Connection connection, Context context, Identity identity) throws SQLException {
        var pin = select(connection, context, identity.scope);
        if (pin != null) {
            if (!pin.identity.equals(identity) || pin.occupied && (pin.owner != context.run || !pin.token.equals(
                    context.token))
                    || pin.occupied && pin.drained || !pin.occupied && !pin.drained) {
                throw unavailable();
            }
            if (!pin.occupied) {
                update(connection, context, pin, true, false);
            }
            return;
        }
        String keyId = context.deployment.currentKeyId();
        byte[] pinMac = pinMac(context.deployment, identity, keyId);
        var value = new Pin(identity, keyId, pinMac, context.run, context.token, true, false, 0, keyId, null);
        try (var statement = statement(connection, context,
                "INSERT INTO " + table(context, "dh_dataforge_access_group_pin")
                        + "(scope,data_access_id,target_database_id,schema_name,table_name,logical_resource,physical_fingerprint,structure_fingerprint,config_fingerprint,"
                        + "pin_key_id,pin_mac,owner_run_id,owner_token,occupied,executor_drained,revision,state_key_id,state_mac) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,true,false,0,?,?)")) {
            statement.setString(1, identity.scope);
            statement.setLong(2, identity.task);
            statement.setLong(3, identity.target);
            statement.setString(4, identity.schema);
            statement.setString(5, identity.table);
            statement.setString(6, identity.logical);
            statement.setString(7, identity.physical);
            statement.setString(8, identity.structure);
            statement.setString(9, identity.configuration);
            statement.setString(10, keyId);
            statement.setBytes(11, pinMac);
            statement.setLong(12, context.run);
            statement.setString(13, context.token);
            statement.setString(14, keyId);
            statement.setBytes(15, stateMac(context.deployment, value));
            if (statement.executeUpdate() != 1) {
                throw unavailable();
            }
        }
    }

    /**
     * 按固定范围读取表组身份及占用状态，校验认证码并按需锁定记录。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @param scope 范围
     * @return 固定引用
     */
    private Pin select(Connection connection, Context context, String scope) throws SQLException {
        return select(connection, context, scope, true);
    }

    /**
     * 按固定范围读取表组身份及占用状态，校验认证码并按需锁定记录。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @param scope 范围
     * @param lock 锁
     * @return 固定引用
     */
    private Pin select(Connection connection, Context context, String scope, boolean lock) throws SQLException {
        try (var statement = statement(connection, context,
                "SELECT data_access_id,target_database_id,schema_name,table_name,logical_resource,"
                        + "physical_fingerprint,structure_fingerprint,config_fingerprint,pin_key_id,pin_mac,owner_run_id,owner_token,occupied,executor_drained,revision,state_key_id,state_mac FROM "
                        + table(context,
                        "dh_dataforge_access_group_pin") + " WHERE scope=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, scope);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                var identity = new Identity(scope, row.getLong(1), row.getLong(2), row.getString(3), row.getString(4),
                        row.getString(5),
                        row.getString(6), row.getString(7), row.getString(8));
                var pin = new Pin(identity, row.getString(9), row.getBytes(10), row.getLong(11), row.getString(12),
                        row.getBoolean(13), row.getBoolean(14),
                        row.getLong(15), row.getString(16), row.getBytes(17));
                if (row.next() || pin.mac == null || pin.stateMac == null
                        || !MessageDigest.isEqual(pin.mac, pinMac(context.deployment, identity, pin.keyId))
                        || !MessageDigest.isEqual(pin.stateMac, stateMac(context.deployment, pin))) {
                    throw unavailable();
                }
                return pin;
            }
        }
    }

    /**
     * 按修订号更新表组持有者与占用状态，并重算状态认证码。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @param prior 先前
     * @param occupied 已占用
     * @param drained 已清空
     */
    private void update(Connection connection, Context context, Pin prior, boolean occupied,
            boolean drained) throws SQLException {
        var next = new Pin(prior.identity, prior.keyId, prior.mac, context.run, context.token, occupied, drained,
                Math.incrementExact(prior.revision), context.deployment.currentKeyId(), null);
        try (var statement = statement(connection, context, "UPDATE " + table(context, "dh_dataforge_access_group_pin")
                + " SET owner_run_id=?,owner_token=?,occupied=?,executor_drained=?,revision=?,state_key_id=?,state_mac=?,updated_at=clock_timestamp() WHERE scope=? AND revision=?")) {
            statement.setLong(1, next.owner);
            statement.setString(2, next.token);
            statement.setBoolean(3, occupied);
            statement.setBoolean(4, drained);
            statement.setLong(5, next.revision);
            statement.setString(6, next.stateKeyId);
            statement.setBytes(7, stateMac(context.deployment, next));
            statement.setString(8, prior.identity.scope);
            statement.setLong(9, prior.revision);
            if (statement.executeUpdate() != 1) {
                throw unavailable();
            }
        }
    }

    /**
     * 计算绑定表组物理目标身份及密钥版本的 HMAC 认证码。
     *
     * @param deployment 部署
     * @param value 待处理的值
     * @param keyId 键编号
     * @return 用于完整性校验的 HMAC 认证码字节
     */
    private byte[] pinMac(Deployment deployment, Identity value, String keyId) {
        return mac(deployment, keyId, "ACCESS_GROUP_PIN_V1", value.scope, Long.toString(value.task),
                Long.toString(value.target),
                value.schema, value.table, value.logical, value.physical, value.structure, value.configuration, keyId);
    }

    /**
     * 计算绑定表组固定身份、执行持有者、令牌及修订号的状态认证码。
     *
     * @param deployment 部署
     * @param value 待处理的值
     * @return 用于完整性校验的 HMAC 认证码字节
     */
    private byte[] stateMac(Deployment deployment, Pin value) {
        return mac(deployment, value.stateKeyId, "ACCESS_GROUP_OWNER_V1", value.identity.scope,
                HexFormat.of().formatHex(value.mac),
                Long.toString(value.owner), value.token, Boolean.toString(value.occupied),
                Boolean.toString(value.drained), Long.toString(value.revision), value.stateKeyId);
    }

    /**
     * 从指定密钥派生表组专用认证密钥，再计算规范字段的 HMAC-SHA256。
     *
     * @param deployment 部署
     * @param keyId 键编号
     * @param values 字段值集合
     * @return 用于完整性校验的 HMAC 认证码字节
     */
    private byte[] mac(Deployment deployment, String keyId, String... values) {
        var secret = deployment.keys().get(keyId);
        if (secret == null) {
            throw unavailable();
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getEncoded(), "HmacSHA256"));
            byte[] derived = mac.doFinal("DATAFORGE_GROUP_PIN_AUTH_KEY_V1".getBytes(StandardCharsets.UTF_8));
            try {
                mac.init(new SecretKeySpec(derived, "HmacSHA256"));
                return mac.doFinal(join(values));
            } finally {
                Arrays.fill(derived, (byte) 0);
            }
        } catch (GeneralSecurityException invalid) {
            throw unavailable();
        }
    }

    /**
     * 在控制库独立事务中设置语句及锁超时，执行回调并提交，失败时尽力回滚。
     *
     * @param <T> 处理数据的类型
     * @param context 当前处理上下文
     * @param work 工作
     * @return 同步表组重写
     */
    private <T> T transaction(Context context, Work<T> work) {
        try (var connection = control.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw unavailable();
            }
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(context.deployment.timeout());
                    statement.execute("SET LOCAL statement_timeout=" + context.deployment.timeout() * 1000);
                    statement.execute("SET LOCAL lock_timeout=" + Math.min(context.deployment.timeout(), 5) * 1000);
                }
                T result = work.run(connection);
                connection.commit();
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                    }
                }
            }
        } catch (Exception unknown) {
            throw unavailable();
        }
    }

    /**
     * 创建带控制库查询超时的预编译语句，配置失败时关闭语句资源。
     *
     * @param connection 数据库连接
     * @param context 当前处理上下文
     * @param sql SQL
     * @return 预编译语句
     */
    private PreparedStatement statement(Connection connection, Context context, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(context.deployment.timeout());
            return statement;
        } catch (SQLException failure) {
            try {
                statement.close();
            } catch (SQLException ignored) {
            }
            throw failure;
        }
    }

    /**
     * 对受控 schema 和表名添加 PostgreSQL 标识符引用。
     *
     * @param context 当前处理上下文
     * @param table 表
     * @return 对受控 schema 和表名添加 PostgreSQL 标识符引用文本
     */
    private String table(Context context, String table) {
        return '"' + context.deployment.schema() + "\".\"" + table + '"';
    }

    private record Context(long task, long target, long run, String token, int releaseRevision,
                           String frozen, String configuration, BatchLimits limits, Deployment deployment,
                           boolean recovery) {

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessGroupRewriteContext[redacted]";
        }
    }

    private record Identity(String scope, long task, long target, String schema, String table,
                            String logical, String physical, String structure, String configuration) {

    }

    private record Pin(Identity identity, String keyId, byte[] mac, long owner, String token, boolean occupied,
                       boolean drained, long revision, String stateKeyId, byte[] stateMac) {

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessGroupRewritePin[redacted]";
        }
    }

    @FunctionalInterface
    private interface Work<T> {

        /**
         * 启动工作执行。
         *
         * @param connection 数据库连接
         * @return 工作
         */
        T run(Connection connection) throws SQLException;
    }

    /**
     * 校验指纹为 64 位小写十六进制摘要。
     *
     * @param value 待处理的值
     */
    private static void fingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
    }

    /**
     * 校验目标路由非空、长度受限且不包含空字符。
     *
     * @param value 待处理的值
     */
    private static void route(String value) {
        if (!text(value) || value.length() > 512 || value.indexOf('\0') >= 0) {
            throw invalid();
        }
    }

    /**
     * 判断文本非空且包含有效字符。
     *
     * @param value 待处理的值
     * @return 处理是否成功
     */
    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 拒绝空值或空字符后，以空字符分隔字段并转为 UTF-8 认证原文。
     *
     * @param values 字段值集合
     * @return 转换后的字节序列
     */
    private static byte[] join(String... values) {
        for (String value : values) {
            if (value == null || value.indexOf('\0') >= 0) {
                throw invalid();
            }
        }
        return String.join("\0", values).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 计算输入内容摘要。
     *
     * @param values 字段值集合
     * @return 计算后的摘要或签名字符串
     */
    private static String hash(String... values) {
        return GroupRewriteMaterial.sha256(join(values));
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @return 非法参数异常
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TDengine 组替换要求可信运行、固定驱动、部署独占授权及无脚本无清表的表同步配置");
    }

    /**
     * 构造当前依赖或资源不可用时使用的异常。
     *
     * @return 运行时异常
     */
    private static RuntimeException unavailable() {
        return exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
    }
}
