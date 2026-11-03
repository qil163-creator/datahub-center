package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.constant.ChangeDataSyncType;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.*;
import cn.iocoder.yudao.framework.dbswitch.data.recovery.PostgresTableRewriteStore;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.service.access.config.*;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessGroupRewriteProperties.Deployment;
import cn.iocoder.yudao.module.dataforge.service.execution.RunLease;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_RUN_REVIEW_REQUIRED;

/** 仅负责 ACCESS 授权、逻辑占用及经过认证的原生代次链。
 * 材料加密、CAS 及目标端恢复执行由 DB Switch 负责。 */
@Component
public class AccessTableRewriteCoordinator {

    private final DataSource control;

    private final AccessTableRewriteProperties properties;

    private final DbSwitchRuntime runtime;

    /**
     * 创建同步单表重写实例。
     *
     * @param control 控制
     * @param properties 组件配置
     * @param runtime 运行时
     */
    public AccessTableRewriteCoordinator(DataSource control, AccessTableRewriteProperties properties,
            DbSwitchRuntime runtime) {
        this.control = Objects.requireNonNull(control);
        this.properties = Objects.requireNonNull(properties);
        this.runtime = Objects.requireNonNull(runtime);
    }

    /**
     * 对启用的 InfluxDB 3 主键比较同步校验运行租约，构造单表重写句柄。
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
    public Handle prepare(DataAccessDO access, DataAccessTaskDO run, RunLease lease, SourceDataSourceProperties source,
            TargetDataSourceProperties target,
            AccessDatabaseResolver.ConnectionInfo sourceConnection,
            AccessDatabaseResolver.ConnectionInfo targetConnection) {
        if (!eligible(target) || !properties.isEnabled()) {
            return null;
        }
        if (run == null || lease == null || !Objects.equals(lease.recordId(), run.getId()) || !Objects.equals(
                lease.token(), run.getExecutionToken())) {
            throw invalid();
        }
        return new Handle(
                context(access, run, lease.token(), source, target, sourceConnection, targetConnection, false));
    }

    /**
     * 固定单表恢复上下文，并在进入平台锁事务前逐表校验目标操作权限。
     *
     * @param access 访问
     * @param run 运行
     * @param token 当前执行或租约令牌
     * @param source 来源数据
     * @param target 目标数据
     * @param sourceConnection 来源连接
     * @param targetConnection 目标连接
     * @return 恢复准入
     */
    public RecoveryAdmission prepareRecovery(DataAccessDO access, DataAccessTaskDO run, String token,
            SourceDataSourceProperties source, TargetDataSourceProperties target,
            AccessDatabaseResolver.ConnectionInfo sourceConnection,
            AccessDatabaseResolver.ConnectionInfo targetConnection) {
        if (!eligible(target) || !properties.isEnabled()) {
            throw invalid();
        }
        var context = context(access, run, token, source, target, sourceConnection, targetConnection, true);
        // 执行身份及精确表绑定必须在调用方准入事务获取平台锁之前核验。
        context.tables.forEach(table -> authorizeTarget(context, context.schema, table));
        return new RecoveryAdmission(context);
    }

    /**
     * 判断目标是否为启用主键有序比较增量同步的 InfluxDB 3 数据源。
     *
     * @param target 目标数据
     * @return 处理是否成功
     */
    private static boolean eligible(TargetDataSourceProperties target) {
        return target != null && target.getTargetProductType() == ProductTypeEnum.INFLUXDB3
                && Boolean.TRUE.equals(target.getChangeDataSync()) && ChangeDataSyncType.PK_ORDER_COMPARE.name()
                .equals(target.getChangeDataSyncType());
    }

    /**
     * 校验当前用户、冻结发布配置、精确表范围和目标独占条件，构造单表重写上下文。
     *
     * @param access 访问
     * @param run 运行
     * @param token 当前执行或租约令牌
     * @param source 来源数据
     * @param target 目标数据
     * @param sc 来源连接
     * @param tc 目标连接
     * @param recovery 恢复
     * @return 上下文
     */
    private Context context(DataAccessDO access, DataAccessTaskDO run, String token, SourceDataSourceProperties source,
            TargetDataSourceProperties target,
            AccessDatabaseResolver.ConnectionInfo sc, AccessDatabaseResolver.ConnectionInfo tc, boolean recovery) {
        var principal = SecurityFrameworkUtils.getLoginUser();
        Long actor = principal == null ? null : principal.getId();
        if (access == null || run == null || source == null || actor == null || run.getId() == null || access.getId() == null
                || !Objects.equals(run.getDataAccessId(),
                access.getId()) || run.getConfigJson() == null || run.getReleaseRevision() == null
                || !Integer.valueOf(1).equals(source.getSourceType()) || !Integer.valueOf(1)
                .equals(source.getIncludeOrExclude()) || AccessExactTableSelection.selectedNames(source).isEmpty()
                || Boolean.TRUE.equals(target.getTargetDrop()) || Boolean.TRUE.equals(
                target.getTargetTruncateData()) || Boolean.TRUE.equals(target.getOnlyCreate())
                || text(source.getBeforeSqlScripts()) || text(source.getAfterSqlScripts()) || text(
                target.getBeforeSqlScripts()) || text(target.getAfterSqlScripts())
                || target.getCustomDdlMap() != null && !target.getCustomDdlMap().isEmpty()) {
            throw invalid();
        }
        if (sc == null || tc == null || !Objects.equals(access.getSourceDatabaseId(),
                sc.getDatabaseId()) || !Objects.equals(access.getTargetDatabaseId(), tc.getDatabaseId())
                || sc.getDatabaseId() == null || tc.getDatabaseId() == null || !text(
                sc.getConfigurationRevision()) || !text(tc.getConfigurationRevision())
                || source.getSourceProductType() != sc.getProductType() || target.getTargetProductType() != tc.getProductType()) {
            throw invalid();
        }
        GroupRewriteRecord.owner(token);
        var deployment = properties.authorize(access.getId());
        var sourceDriver = runtime.drivers().find(source.getSourceProductType())
                .orElseThrow(AccessTableRewriteCoordinator::invalid);
        var targetDriver = runtime.drivers().find(target.getTargetProductType())
                .orElseThrow(AccessTableRewriteCoordinator::invalid);
        sourceDriver.verifyUnchanged();
        targetDriver.verifyUnchanged();
        var limits = properties.limits(
                target.getBatchLimits() == null ? BatchLimits.defaults() : target.getBatchLimits(),
                runtime.options().maxCacheBytes());
        var tables = new LinkedHashSet<String>();
        for (String name : AccessExactTableSelection.selectedNames(source)) {
            String mapped = MigrationTableNames.resolve(source, target, name);
            route(mapped);
            if (!tables.add(mapped)) {
                throw invalid();
            }
        }
        if (tables.isEmpty() || tables.size() > 4096) {
            throw invalid();
        }
        route(target.getTargetSchema());
        String frozen = AccessRewriteRunGuard.hash(run.getConfigJson());
        String configuration = hash("ACCESS_TABLE_CONFIG_V1", frozen, run.getReleaseRevision().toString(),
                sc.getDatabaseId().toString(), tc.getDatabaseId().toString(),
                sc.getConfigurationRevision(), tc.getConfigurationRevision(), source.getSourceProductType().name(),
                target.getTargetProductType().name(),
                sourceDriver.profileId(), sourceDriver.fingerprint(), targetDriver.profileId(),
                targetDriver.fingerprint(), limits.toString());
        return new Context(access.getId(), tc.getDatabaseId(), run.getId(), token, run.getReleaseRevision(), frozen,
                configuration,
                actor, target.getTargetSchema(), Set.copyOf(tables), limits, deployment, recovery);
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
            return "AccessTableRecoveryAdmission[redacted]";
        }
    }

    /**
     * 在当前控制库事务内锁定运行，认证旧占用并接管允许恢复的单表范围。
     *
     * @param connection 数据库连接
     * @param admission 准入
     * @return 恢复句柄
     */
    public RecoveryHandle admitRecovery(Connection connection, RecoveryAdmission admission) {
        Context context = Objects.requireNonNull(admission).context;
        try {
            requireTransaction(connection);
            lockOwner(connection, context, false);
            var pins = new LinkedHashMap<String, Pin>();
            for (String scope : scopes(connection, context)) {
                var pin = select(connection, context, scope);
                if (pin == null) {
                    throw unavailable();
                }
                if (pin.owner != context.run) {
                    continue; // 归属者的 MAC 通过认证后才进行过滤。
                }
                if (!pin.occupied || !pin.drained || pin.manual || pin.identity == null || pin.structure == null || pin.token.equals(
                        context.token)) {
                    throw unavailable();
                }
                requireConfiguration(context, pin);
                pins.put(scope, pin);
            }
            if (pins.isEmpty()) {
                throw unavailable();
            }
            var admitted = new LinkedHashMap<String, Pin>();
            for (var pin : pins.values()) {
                admitted.put(pin.scope,
                        update(connection, context, pin, pin.identity, pin.structure, true, false, false));
            }
            return new RecoveryHandle(context, admitted);
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    /** 与现有经审计的 RESOLVE 命令处于同一个 Spring 绑定事务。 */
    public void assertResolved(Connection connection, long task, long run) {
        try {
            var context = inspection(connection, task, run, true);
            if (context == null) {
                return;
            }
            for (String scope : scopes(connection, context)) {
                var pin = select(connection, context, scope);
                if (pin == null) {
                    throw unavailable();
                }
                if (pin.owner == run && (pin.occupied || !pin.drained || pin.manual)) {
                    throw unavailable();
                }
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    /** 只读提示，不能转移归属权或授权目标端请求。 */
    public AccessGroupRewriteCoordinator.RecoveryStatus recoveryStatus(Connection connection, long task, long run) {
        try {
            var context = inspection(connection, task, run, false);
            if (context == null) {
                return new AccessGroupRewriteCoordinator.RecoveryStatus(false, false, false,
                        "此记录没有登记需要恢复的整表材料。");
            }
            boolean found = false, released = true, drained = true, manual = false;
            for (String scope : scopes(connection, context, false)) {
                var pin = select(connection, context, scope, false);
                if (pin == null) {
                    throw unavailable();
                }
                if (pin.owner != run) {
                    continue;
                }
                found = true;
                released &= !pin.occupied && pin.drained;
                drained &= pin.drained;
                manual |= pin.manual || pin.identity == null;
            }
            if (!found) {
                return new AccessGroupRewriteCoordinator.RecoveryStatus(false, false, false,
                        "此记录没有登记需要恢复的整表材料。");
            }
            if (released) {
                return new AccessGroupRewriteCoordinator.RecoveryStatus(true, false, true,
                        "整表材料已核验并释放；仍须核查原同步完整输出并提交审核结论。");
            }
            if (manual) {
                return new AccessGroupRewriteCoordinator.RecoveryStatus(true, false, false,
                        "原生创建或身份探测结果不明，不能按同名表自动认领；请保留占位并核验原生代次及精确临时资源。");
            }
            if (!drained) {
                return new AccessGroupRewriteCoordinator.RecoveryStatus(true, false, false,
                        "执行资源尚未确认退出；停止请求或租约到期不能代替服务端退出证据。");
            }
            try (var s = statement(connection, context,
                    "SELECT run_status,occupies_slot,review_required,reconcile_token,reconcile_lease_until>clock_timestamp() FROM " + table(
                            context, "dh_dataforge_access_task") + " WHERE id=? AND data_access_id=? AND deleted=0")) {
                s.setLong(1, run);
                s.setLong(2, task);
                try (var r = s.executeQuery()) {
                    if (!r.next()) {
                        throw unavailable();
                    }
                    if (r.getString(4) != null && r.getBoolean(5)) {
                        return new AccessGroupRewriteCoordinator.RecoveryStatus(true, false, false,
                                "恢复已受理，正在执行或核查退出；受理不代表恢复成功。");
                    }
                    if (r.getInt(1) != 5 || !r.getBoolean(2) || !r.getBoolean(3)) {
                        return new AccessGroupRewriteCoordinator.RecoveryStatus(true, false, false,
                                "当前记录不处于待核查占位状态，请刷新记录。");
                    }
                }
            }
            return new AccessGroupRewriteCoordinator.RecoveryStatus(true, true, false,
                    "已确认原执行器退出，可按已认证的原生代次恢复整表材料；执行前仍将复核权限及原配置。");
        } catch (RuntimeException | SQLException invalid) {
            return new AccessGroupRewriteCoordinator.RecoveryStatus(true, false, false,
                    "整表恢复配置或记录完整性核验失败，请保留占位并联系管理员核查。");
        }
    }

    /**
     * 校验控制 schema、任务与运行绑定，构造读取单表恢复状态的检查上下文。
     *
     * @param connection 数据库连接
     * @param task 任务记录
     * @param run 运行
     * @param lock 锁
     * @return 上下文
     */
    private Context inspection(Connection connection, long task, long run, boolean lock) throws SQLException {
        requireTransaction(connection);
        String schema = properties.getControlSchema();
        if (schema == null || !schema.matches("[a-z][a-z0-9_-]{0,62}")) {
            throw unavailable();
        }
        String prefix = '"' + schema + "\".";
        try (var s = connection.prepareStatement(
                "SELECT id FROM " + prefix + "dh_dataforge_access WHERE id=? AND deleted=0" + (lock ? " FOR UPDATE" : ""))) {
            s.setQueryTimeout(10);
            s.setLong(1, task);
            try (var r = s.executeQuery()) {
                if (!r.next() || r.getLong(1) != task || r.next()) {
                    throw unavailable();
                }
            }
        }
        try (var s = connection.prepareStatement(
                "SELECT data_access_id FROM " + prefix + "dh_dataforge_access_task WHERE id=? AND deleted=0" + (lock ? " FOR UPDATE" : ""))) {
            s.setQueryTimeout(10);
            s.setLong(1, run);
            try (var r = s.executeQuery()) {
                if (!r.next() || r.getLong(1) != task || r.next()) {
                    throw unavailable();
                }
            }
        }
        try (var s = connection.prepareStatement(
                "SELECT scope FROM " + prefix + "dh_dataforge_access_table_pin WHERE data_access_id=? LIMIT 1")) {
            s.setQueryTimeout(10);
            s.setLong(1, task);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
            }
        }
        var deployment = properties.authorize(task);
        return new Context(task, 0, run, "", 0, "", "", 0, "", Set.of(),
                new TableRewriteLimits(BatchLimits.defaults(), 100_000, 4096, 64L * 1024 * 1024), deployment, false);
    }

    public class Handle implements TableRewriteBindingProvider {

        private final Context context;

        private final Map<String, Pin> pins = new LinkedHashMap<>();

        private final Map<String, TableRewriteBinding> bindings = new LinkedHashMap<>();

        private volatile boolean finishing, confirmedExecutorDrain;

        private boolean released, admissionUnconfirmed;

        /**
         * 创建句柄实例。
         *
         * @param context 当前处理上下文
         */
        private Handle(Context context) {
            this.context = context;
        }

        /**
         * 仅当所有保留固定记录的实际退出凭据均已持久确认时才返回 true。
         *
         * @return 判断结果
         */
        public boolean hasConfirmedExecutorDrain() {
            return confirmedExecutorDrain;
        }

        /**
         * 授权管理探测。
         *
         * @param schema 结构
         * @param table 表
         * @param logical 逻辑
         */
        @Override
        public synchronized void authorizeManagementProbe(String schema, String table, String logical) {
            if (finishing || context.recovery || pins.size() >= 4096) {
                throw unavailable();
            }
            route(schema);
            route(table);
            fingerprint(logical);
            if (!context.schema.equals(schema) || !context.tables.contains(table)) {
                throw unavailable();
            }
            authorizeTarget(context, schema, table);
            String scope = scope(context, schema, table);
            try {
                var pin = transaction(context, connection -> {
                    lockOwner(connection, context, false);
                    var prior = select(connection, context, scope);
                    if (prior == null) {
                        return insert(connection, context, scope, schema, table, logical);
                    }
                    requireConfiguration(context, prior);
                    if (!prior.logical.equals(
                            logical) || prior.manual || prior.occupied && (prior.owner != context.run || !prior.token.equals(
                            context.token) || prior.drained)
                            || !prior.occupied && !prior.drained) {
                        throw unavailable();
                    }
                    return prior.occupied ? prior : update(connection, context, prior, prior.identity, prior.structure,
                            true, false, false);
                });
                pins.put(scope, pin);
            } catch (RuntimeException unknown) {
                admissionUnconfirmed = true;
                throw unknown;
            }
        }

        /**
         * 解析句柄配置或身份。
         *
         * @param schema 结构
         * @param table 表
         * @param logical 逻辑
         * @param identity 身份
         * @param structure 结构
         * @return 单表重写绑定
         */
        @Override
        public synchronized TableRewriteBinding resolve(String schema, String table, String logical,
                TableRewriteIdentity identity, String structure) {
            if (finishing || context.recovery) {
                throw unavailable();
            }
            fingerprint(structure);
            Objects.requireNonNull(identity);
            String scope = scope(context, schema, table);
            Pin reserved = pins.get(scope);
            if (reserved == null || !reserved.logical.equals(logical)) {
                throw unavailable();
            }
            try {
                var ready = transaction(context, connection -> {
                    Pin pin = guard(connection, context, reserved, null, false);
                    if (pin.identity != null && (!pin.identity.equals(identity) || !Objects.equals(pin.structure,
                            structure))) {
                        throw unavailable();
                    }
                    return pin.identity == null ? update(connection, context, pin, identity, structure, true, false,
                            false) : pin;
                });
                pins.put(scope, ready);
                return binding(ready);
            } catch (RuntimeException unknown) {
                admissionUnconfirmed = true;
                throw unknown;
            }
        }

        /**
         * 获取句柄的绑定。
         *
         * @param pin 固定引用
         * @return 单表重写绑定
         */
        private TableRewriteBinding binding(Pin pin) {
            var binding = new TableRewriteBinding(pin.scope,
                    hash(context.configuration, pin.schema, pin.table, pin.structure), pin.identity, context.token,
                    store(context, pin, false), context.limits, true, () -> {
                if (finishing) {
                    throw unavailable();
                }
                transaction(context, c -> {
                    guard(c, context, pin, null, false);
                    return null;
                });
            });
            bindings.put(pin.scope, binding);
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
            try {
                for (Pin pin : pins.values()) {
                    transaction(context, c -> {
                        var current = guard(c, context, pin, null, true);
                        if (!current.drained) {
                            update(c, context, current, current.identity, current.structure, true, true,
                                    current.manual);
                        }
                        return null;
                    });
                }
                confirmedExecutorDrain = true;
                var next = new LinkedHashMap<String, TableRewriteIdentity>();
                boolean pending = false;
                for (Pin pin : pins.values()) {
                    if (pin.identity == null) {
                        transaction(context, c -> {
                            var current = guard(c, context, pin, null, true);
                            update(c, context, current, current.identity, current.structure, true, true, true);
                            return null;
                        });
                        pending = true;
                        continue;
                    }
                    var records = records(context, pin, true);
                    boolean manual = records.stream().anyMatch(
                            r -> r.phase() != TableRewriteRecord.Phase.APPLIED && !r.permitsAutomaticReconciliation());
                    if (manual) {
                        transaction(context, c -> {
                            var current = guard(c, context, pin, null, true);
                            update(c, context, current, current.identity, current.structure, true, true, true);
                            return null;
                        });
                    }
                    if (records.stream().anyMatch(r -> r.phase() != TableRewriteRecord.Phase.APPLIED)) {
                        pending = true;
                        continue;
                    }
                    next.put(pin.scope, advanceGeneration(pin, records));
                }
                if (pending) {
                    return false;
                }
                transaction(context, c -> {
                    lockOwner(c, context, true);
                    for (Pin pin : pins.values()) {
                        var current = requirePin(c, context, pin, true);
                        if (!current.drained) {
                            throw unavailable();
                        }
                        update(c, context, current, next.get(pin.scope), current.structure, false, true, false);
                    }
                    return null;
                });
                released = true;
                return true;
            } catch (RuntimeException unconfirmed) {
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
            return "AccessTableRewriteHandle[redacted]";
        }
    }

    public record RecoveryTable(String schema, String table, TableRewriteBinding binding, TableRewriteRecord record) {

        /**
         * 创建恢复表实例。
         *
         * @param schema 结构
         * @param table 表
         * @param binding 绑定
         * @param record 运行记录
         */
        public RecoveryTable {
            Objects.requireNonNull(binding);
            Objects.requireNonNull(record);
        }

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessTableRecoveryTable[redacted]";
        }
    }

    public final class RecoveryHandle extends Handle {

        /**
         * 创建恢复句柄实例。
         *
         * @param context 当前处理上下文
         * @param pins 固定引用
         */
        private RecoveryHandle(Context context, Map<String, Pin> pins) {
            super(context);
            super.pins.putAll(pins);
            pins.values().forEach(super::binding);
        }

        /**
         * 拒绝继续使用已进入完成阶段的恢复句柄，并逐项复核绑定资源的执行归属。
         */
        public synchronized void assertOwnership() {
            if (super.finishing) {
                throw unavailable();
            }
            super.bindings.values().forEach(TableRewriteBinding::checkOwnership);
        }

        /**
         * 不读取源端数据；执行目标端操作前，认证每一页、每个头记录及所有待处理材料。
         *
         * @return 不读取源端数据；执行目标端操作前，认证每一页、每个头记录及所有待处理材料结果集合
         */
        public synchronized List<RecoveryTable> pendingTables() {
            assertOwnership();
            var output = new ArrayList<RecoveryTable>();
            for (Pin pin : super.pins.values()) {
                for (var record : records(super.context, pin, false)) {
                    if (record.phase() == TableRewriteRecord.Phase.APPLIED) {
                        continue;
                    }
                    var binding = super.bindings.get(pin.scope);
                    if (!record.permitsAutomaticReconciliation() || !record.material().oldIdentity()
                            .equals(pin.identity)
                            || !record.material().bindingFingerprint().equals(binding.fingerprint())) {
                        throw unavailable();
                    }
                    var claimed = record.ownerToken().equals(super.context.token) ? record : binding.store()
                            .claimRecovery(record.key(), record.revision(), super.context.token)
                            .orElseThrow(AccessTableRewriteCoordinator::unavailable);
                    output.add(new RecoveryTable(pin.schema, pin.table, binding, claimed));
                }
            }
            return List.copyOf(output);
        }
    }

    /**
     * 校验持久执行人和精确目标表绑定。
     *
     * @param context 当前处理上下文
     * @param schema 结构
     * @param table 表
     */
    private void authorizeTarget(Context context, String schema, String table) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor != context.actor) {
            throw unavailable();
        }
        cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                ProductTypeEnum.INFLUXDB3, schema, table);
    }

    /**
     * 在执行权门禁下有界读取当前单表的恢复操作记录。
     *
     * @param context 当前处理上下文
     * @param pin 固定引用
     * @param completion 完成
     * @return 在执行权门禁下有界读取当前单表的恢复操作记录结果集合
     */
    private List<TableRewriteRecord> records(Context context, Pin pin, boolean completion) {
        var keys = transaction(context, c -> {
            guard(c, context, pin, null, completion);
            var result = new ArrayList<TableRewriteKey>();
            try (var s = statement(c, context, "SELECT operation FROM " + table(context,
                    "dh_dataforge_access_table_recovery") + " WHERE scope=? AND logical_resource=? ORDER BY operation LIMIT 4097")) {
                s.setString(1, pin.scope);
                s.setString(2, pin.logical);
                try (var rows = s.executeQuery()) {
                    while (rows.next()) {
                        result.add(new TableRewriteKey(pin.scope, pin.logical, rows.getString(1)));
                    }
                }
            }
            if (result.size() > 4096) {
                throw unavailable();
            }
            return result;
        });
        var store = store(context, pin, completion);
        var records = new ArrayList<TableRewriteRecord>();
        for (var key : keys) {
            var record = store.read(key).orElseThrow(AccessTableRewriteCoordinator::unavailable);
            if (!record.material().schema().equals(pin.schema) || !record.material().table().equals(pin.table)
                    || record.material().definition().product() != ProductTypeEnum.INFLUXDB3
                    || !record.material().bindingFingerprint()
                    .equals(hash(context.configuration, pin.schema, pin.table, pin.structure))) {
                throw unavailable();
            }
            records.add(record);
        }
        return List.copyOf(records);
    }

    /**
     * 沿已应用的重写记录推进物理身份代次，拒绝分叉、未应用记录及循环。
     *
     * @param pin 固定引用
     * @param records 记录
     * @return 单表重写身份
     */
    private TableRewriteIdentity advanceGeneration(Pin pin, List<TableRewriteRecord> records) {
        var edges = new HashMap<TableRewriteIdentity, TableRewriteIdentity>();
        for (var record : records) {
            if (record.phase() != TableRewriteRecord.Phase.APPLIED || edges.put(record.material().oldIdentity(),
                    record.newIdentity()) != null) {
                throw unavailable();
            }
        }
        TableRewriteIdentity current = pin.identity;
        var seen = new HashSet<TableRewriteIdentity>();
        while (edges.containsKey(current)) {
            if (!seen.add(current)) {
                throw unavailable();
            }
            current = edges.get(current);
        }
        return current;
    }

    /**
     * 构造带固定范围、密钥和运行门禁的 PostgreSQL 单表恢复存储。
     *
     * @param context 当前处理上下文
     * @param pin 固定引用
     * @param completion 完成
     * @return PostgreSQL单表重写存储
     */
    private PostgresTableRewriteStore store(Context context, Pin pin, boolean completion) {
        return new PostgresTableRewriteStore(control,
                context.deployment.schema() + ".dh_dataforge_access_table_recovery",
                context.deployment.schema() + ".dh_dataforge_access_table_page",
                pin.scope, context.deployment.currentKeyId(), context.deployment.keys(), context.limits,
                context.deployment.timeout(), (c, k) -> guard(c, context, pin, k, completion));
    }

    /**
     * 锁定运行归属并核对重写范围及逻辑目标，再校验当前单表占用。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param pin 固定引用
     * @param key 标识键
     * @param completion 完成
     * @return 固定引用
     */
    private Pin guard(Connection c, Context context, Pin pin, TableRewriteKey key,
            boolean completion) throws SQLException {
        lockOwner(c, context, completion);
        if (key != null && (!pin.scope.equals(key.scope()) || !pin.logical.equals(key.logicalResource()))) {
            throw unavailable();
        }
        return requirePin(c, context, pin, completion);
    }

    /**
     * 校验单表执行令牌、占用状态、连接配置和固定物理身份。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param expected 预期值
     * @param completion 完成
     * @return 固定引用
     */
    private Pin requirePin(Connection c, Context context, Pin expected, boolean completion) throws SQLException {
        Pin pin = select(c, context, expected.scope);
        if (pin == null || !pin.occupied || pin.owner != context.run || !pin.token.equals(
                context.token) || !completion && pin.drained
                || !pin.logical.equals(expected.logical) || !pin.configuration.equals(
                expected.configuration) || !Objects.equals(pin.identity, expected.identity)
                || !Objects.equals(pin.structure, expected.structure)) {
            throw unavailable();
        }
        return pin;
    }

    /**
     * 要求显式事务并锁定当前任务、运行及执行令牌的归属关系。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param completion 完成
     */
    private void lockOwner(Connection c, Context context, boolean completion) throws SQLException {
        requireTransaction(c);
        AccessRewriteRunGuard.lock(c, new AccessRewriteRunGuard.Owner(context.task, context.run, context.token,
                context.recovery, context.revision, context.frozen, context.deployment.schema(),
                context.deployment.timeout()), completion);
    }

    /**
     * 创建绑定当前运行、逻辑目标和连接指纹的单表占用记录及认证码。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param scope 范围
     * @param schema 结构
     * @param name 名称
     * @param logical 逻辑
     * @return 固定引用
     */
    private Pin insert(Connection c, Context context, String scope, String schema, String name,
            String logical) throws SQLException {
        Pin pin = new Pin(scope, context.task, context.target, schema, name, logical, context.configuration, null, null,
                context.run, context.token, true, false, false, 0, context.deployment.currentKeyId());
        try (var s = statement(c, context, "INSERT INTO " + table(context, "dh_dataforge_access_table_pin")
                + "(scope,data_access_id,target_database_id,schema_name,table_name,logical_resource,config_fingerprint,owner_run_id,owner_token,occupied,executor_drained,manual_required,revision,state_key_id,state_mac) VALUES(?,?,?,?,?,?,?,?,?,true,false,false,0,?,?)")) {
            s.setString(1, pin.scope);
            s.setLong(2, pin.task);
            s.setLong(3, pin.target);
            s.setString(4, pin.schema);
            s.setString(5, pin.table);
            s.setString(6, pin.logical);
            s.setString(7, pin.configuration);
            s.setLong(8, pin.owner);
            s.setString(9, pin.token);
            s.setString(10, pin.keyId);
            s.setBytes(11, mac(context.deployment, pin));
            if (s.executeUpdate() != 1) {
                throw unavailable();
            }
        }
        return pin;
    }

    /**
     * 按修订号更新单表身份、执行归属与恢复状态，并生成新的状态认证码。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param prior 先前
     * @param identity 身份
     * @param structure 结构
     * @param occupied 已占用
     * @param drained 已清空
     * @param manual 手工
     * @return 固定引用
     */
    private Pin update(Connection c, Context context, Pin prior, TableRewriteIdentity identity, String structure,
            boolean occupied, boolean drained, boolean manual) throws SQLException {
        Pin next = new Pin(prior.scope, prior.task, prior.target, prior.schema, prior.table, prior.logical,
                prior.configuration, structure, identity,
                context.run, context.token, occupied, drained, manual, Math.incrementExact(prior.revision),
                context.deployment.currentKeyId());
        try (var s = statement(c, context, "UPDATE " + table(context,
                "dh_dataforge_access_table_pin") + " SET identity_format=?,identity_document=?,structure_fingerprint=?,owner_run_id=?,owner_token=?,occupied=?,executor_drained=?,manual_required=?,revision=?,state_key_id=?,state_mac=?,updated_at=clock_timestamp() WHERE scope=? AND revision=?")) {
            s.setString(1, identity == null ? null : identity.format());
            s.setString(2, identity == null ? null : identity.document());
            s.setString(3, structure);
            s.setLong(4, next.owner);
            s.setString(5, next.token);
            s.setBoolean(6, occupied);
            s.setBoolean(7, drained);
            s.setBoolean(8, manual);
            s.setLong(9, next.revision);
            s.setString(10, next.keyId);
            s.setBytes(11, mac(context.deployment, next));
            s.setString(12, prior.scope);
            s.setLong(13, prior.revision);
            if (s.executeUpdate() != 1) {
                throw unavailable();
            }
        }
        return next;
    }

    /**
     * 按固定范围读取单表身份及占用状态，校验认证码并按需锁定记录。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param scope 范围
     * @return 固定引用
     */
    private Pin select(Connection c, Context context, String scope) throws SQLException {
        return select(c, context, scope, true);
    }

    /**
     * 按固定范围读取单表身份及占用状态，校验认证码并按需锁定记录。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param scope 范围
     * @param lock 锁
     * @return 固定引用
     */
    private Pin select(Connection c, Context context, String scope, boolean lock) throws SQLException {
        try (var s = statement(c, context,
                "SELECT data_access_id,target_database_id,schema_name,table_name,logical_resource,config_fingerprint,structure_fingerprint,identity_format,identity_document,owner_run_id,owner_token,occupied,executor_drained,manual_required,revision,state_key_id,state_mac FROM " + table(
                        context, "dh_dataforge_access_table_pin") + " WHERE scope=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, scope);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                String format = r.getString(8), document = r.getString(9);
                if ((format == null) != (document == null)) {
                    throw unavailable();
                }
                var pin = new Pin(scope, r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getString(7),
                        format == null ? null : new TableRewriteIdentity(format, document), r.getLong(10),
                        r.getString(11), r.getBoolean(12), r.getBoolean(13), r.getBoolean(14), r.getLong(15),
                        r.getString(16));
                byte[] actual = r.getBytes(17);
                if (actual == null || !MessageDigest.isEqual(mac(context.deployment, pin),
                        actual) || pin.task != context.task || r.next()) {
                    throw unavailable();
                }
                return pin;
            }
        }
    }

    /**
     * 有界读取任务所属单表固定范围，并按调用要求锁定记录。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @return 有界读取任务所属单表固定范围，并按调用要求锁定记录结果集合
     */
    private List<String> scopes(Connection c, Context context) throws SQLException {
        return scopes(c, context, true);
    }

    /**
     * 有界读取任务所属单表固定范围，并按调用要求锁定记录。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param lock 锁
     * @return 有界读取任务所属单表固定范围，并按调用要求锁定记录结果集合
     */
    private List<String> scopes(Connection c, Context context, boolean lock) throws SQLException {
        var scopes = new ArrayList<String>();
        try (var s = statement(c, context, "SELECT scope FROM " + table(context,
                "dh_dataforge_access_table_pin") + " WHERE data_access_id=? ORDER BY scope LIMIT 4097" + (lock ? " FOR UPDATE" : ""))) {
            s.setLong(1, context.task);
            try (var rows = s.executeQuery()) {
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

    /**
     * 核对单表占用的目标数据源、schema、表范围及配置指纹仍与当前上下文一致。
     *
     * @param context 当前处理上下文
     * @param pin 固定引用
     */
    private void requireConfiguration(Context context, Pin pin) {
        if (pin.target != context.target || !pin.schema.equals(context.schema) || !context.tables.contains(
                pin.table) || !pin.configuration.equals(context.configuration)
                || !pin.scope.equals(scope(context, pin.schema, pin.table))) {
            throw unavailable();
        }
    }

    /**
     * 对单表重写的固定身份、执行归属和状态修订计算 HMAC-SHA256 认证码。
     *
     * @param deployment 部署
     * @param pin 固定引用
     * @return 用于完整性校验的 HMAC 认证码字节
     */
    private byte[] mac(Deployment deployment, Pin pin) {
        try {
            var key = deployment.keys().get(pin.keyId);
            if (key == null) {
                throw unavailable();
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return mac.doFinal(AccessRewriteRunGuard.join("ACCESS_TABLE_PIN_V1", pin.scope, Long.toString(pin.task),
                    Long.toString(pin.target), pin.schema, pin.table, pin.logical, pin.configuration,
                    pin.structure, pin.identity == null ? null : pin.identity.format(),
                    pin.identity == null ? null : pin.identity.document(), Long.toString(pin.owner), pin.token,
                    Boolean.toString(pin.occupied), Boolean.toString(pin.drained), Boolean.toString(pin.manual),
                    Long.toString(pin.revision), pin.keyId));
        } catch (java.security.GeneralSecurityException failure) {
            throw unavailable();
        }
    }

    /**
     * 在控制库独立事务内执行回调，提交成功结果，异常时尽力回滚并转为待核查错误。
     *
     * @param <T> 处理数据的类型
     * @param context 当前处理上下文
     * @param work 工作
     * @return 同步单表重写
     */
    private <T> T transaction(Context context, Work<T> work) {
        try (var c = control.getConnection()) {
            c.setAutoCommit(false);
            try {
                T value = work.run(c);
                c.commit();
                return value;
            } catch (Exception failure) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw unavailable();
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    /**
     * 确认控制库连接已关闭自动提交，保证占用校验位于真实事务内。
     *
     * @param c 列
     */
    private static void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit()) {
            throw unavailable();
        }
    }

    /**
     * 创建带部署查询超时的控制库预编译语句。
     *
     * @param c 列
     * @param context 当前处理上下文
     * @param sql SQL
     * @return 预编译语句
     */
    private static PreparedStatement statement(Connection c, Context context, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(context.deployment.timeout());
        return s;
    }

    /**
     * 对受控 schema 和表名添加 PostgreSQL 标识符引用。
     *
     * @param context 当前处理上下文
     * @param table 表
     * @return 对受控 schema 和表名添加 PostgreSQL 标识符引用文本
     */
    private static String table(Context context, String table) {
        return '"' + context.deployment.schema() + "\".\"" + table + '"';
    }

    /**
     * 按协议、任务、目标数据源和精确表身份计算单表占用范围摘要。
     *
     * @param c 列
     * @param schema 结构
     * @param table 表
     * @return 按协议、任务、目标数据源和精确表身份计算单表占用范围摘要文本
     */
    private static String scope(Context c, String schema, String table) {
        return hash("ACCESS_TABLE_SCOPE_V1", Long.toString(c.task), Long.toString(c.target), schema, table);
    }

    /**
     * 计算输入内容摘要。
     *
     * @param values 字段值集合
     * @return 计算后的摘要或签名字符串
     */
    private static String hash(String... values) {
        return GroupRewriteMaterial.sha256(AccessRewriteRunGuard.join(values));
    }

    /**
     * 校验目标路由非空、长度受限且不包含控制字符。
     *
     * @param value 待处理的值
     */
    private static void route(String value) {
        if (!text(value) || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
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
     * 判断文本非空且包含有效字符。
     *
     * @param value 待处理的值
     * @return 处理是否成功
     */
    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @return 非法参数异常
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Influx 整表替换要求固定授权表、可信驱动、独占目标和无脚本无清表的同步配置");
    }

    /**
     * 构造当前依赖或资源不可用时使用的异常。
     *
     * @return 运行时异常
     */
    private static RuntimeException unavailable() {
        return exception(DATA_ACCESS_RUN_REVIEW_REQUIRED);
    }

    @FunctionalInterface
    private interface Work<T> {

        /**
         * 启动工作执行。
         *
         * @param c 列
         * @return 工作
         */
        T run(Connection c) throws SQLException;
    }

    private record Context(long task, long target, long run, String token, int revision, String frozen,
                           String configuration, long actor, String schema, Set<String> tables,
                           TableRewriteLimits limits, Deployment deployment, boolean recovery) {

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessTableContext[redacted]";
        }
    }

    private record Pin(String scope, long task, long target, String schema, String table, String logical,
                       String configuration, String structure, TableRewriteIdentity identity, long owner, String token,
                       boolean occupied, boolean drained, boolean manual, long revision, String keyId) {

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "AccessTablePin[redacted]";
        }
    }
}
