package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.data.query.OperationBudget;
import cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowOriginCapture;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.sql.Connection;
import java.util.*;

/** 仅负责文件领域准入；物理标识、目录校验、SQL 改写及 JDBC 生命周期由 DB Switch 负责。 */
@Component
public class EtlFileNativeSourceGuard {

    @Resource
    private DbSwitchRuntime runtime;

    public record Identity(String relationHash, String storageHash) {

    }

    static final class Unproven extends RuntimeException {

        /**
         * 创建未验证实例。
         */
        Unproven() {
            super("Native source identity is not proven", null, false, false);
        }
    }

    /** 实际读取器持有这一已打开的 SELECT 事务；校验过程绝不修改或关闭它。 */
    public void validateActual(Connection connection, String authorizedSql, String defaultSchema, Identity expected) {
        try {
            if (connection == null || connection.isClosed() || connection.getAutoCommit() || expected == null
                    || Thread.currentThread().isInterrupted()) {
                throw new Unproven();
            }
            var plan = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL, authorizedSql, defaultSchema);
            if (plan.isWildcard() || plan.getSourceColumns().isEmpty()) {
                throw new Unproven();
            }
            String reference = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL,
                    plan.getSchemaName(), plan.getTableName());
            // 正在执行的 SELECT 已持有事务对应的关系锁，并证明原生 SELECT 可用。
            // 复用的每条原生目录查询语句均采用组件的五秒查询超时。
            String storage = PostgresRelationIdentity.storageFingerprint(connection, reference, false);
            String relation = PostgresRelationIdentity.fingerprint(connection, reference);
            if (!Objects.equals(storage, expected.storageHash()) || !Objects.equals(relation,
                    expected.relationHash())) {
                throw new Unproven();
            }
        } catch (Exception changed) {
            throw new Unproven();
        }
    }

    /** 这一有时限的只读源端事务内不持有平台锁，也不调用 RPC；不返回源数据行。 */
    public Identity inspect(EtlConnectionInfo info, String authorizedSql, Long actor) {

        if (info == null || info.getProductType() != ProductTypeEnum.POSTGRESQL || actor == null || actor <= 0) {
            throw new Unproven();
        }
        final String reference, probe;
        try {
            var plan = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL, authorizedSql, info.getSchema());
            if (plan.isWildcard() || plan.getSourceColumns().isEmpty()) {
                throw new Unproven();
            }
            reference = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, plan.getSchemaName(),
                    plan.getTableName());
            var columns = List.copyOf(plan.getSourceColumns());
            probe = RestrictedSelectGuard.restrictProjectionWithRowFilters(ProductTypeEnum.POSTGRESQL,
                    plan.getNormalizedSql(), plan.getSchemaName(), plan.getTableName(), columns,
                    new LinkedHashSet<>(columns), List.of("1 = 0")).sql();
        } catch (RuntimeException unsupported) {
            throw new Unproven();
        }
        String scope = "etl-file-native:" + actor;
        final QueryRuntime.Operation operation;
        try {
            operation = runtime.queries().register(scope, scope, UUID.randomUUID().toString(), UUID.randomUUID(),
                    new OperationBudget(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(3),
                            // 共享额度包含目录记录行；下方源端探测仍须返回零行。
                            16, 64 * 1024, 16 * 1024, 1));
        } catch (RuntimeException unavailable) {
            throw new Unproven();
        }
        Identity identity = null;
        QueryRuntime.Termination termination;
        boolean interruptedBefore = Thread.currentThread().isInterrupted();
        try {
            operation.bindWorker();
            try (var session = operation.openSession(EtlRowOriginCapture.spec(info), ConnectionBudget.Role.METADATA)) {
                var connection = session.connection();
                // QueryRuntime 负责此只读事务，其门面拒绝调用方修改状态、提交
                // 或回滚；Session.close 与已确认的操作终止共同释放其锁。
                if (!connection.isReadOnly() || connection.getAutoCommit()) {
                    throw new Unproven();
                }
                String storage = PostgresRelationIdentity.storageFingerprint(connection, reference, true);
                if (!hash(storage)) {
                    throw new Unproven();
                }
                String relation = PostgresRelationIdentity.fingerprint(connection, reference);
                if (!hash(relation)) {
                    throw new Unproven();
                }
                try (var cursor = session.query(scope, probe, List.of())) {
                    var page = cursor.fetch(scope, cursor.id(), 1);
                    if (!page.exhausted() || !page.rows().isEmpty()) {
                        throw new Unproven();
                    }
                }
                operation.check();
                if (!Objects.equals(storage, PostgresRelationIdentity.storageFingerprint(connection, reference, false))
                        || !Objects.equals(relation, PostgresRelationIdentity.fingerprint(connection, reference))) {
                    throw new Unproven();
                }
                identity = new Identity(relation, storage);
            }
        } catch (Exception unavailable) {
            identity = null;
        } finally {
            operation.workerExited();
            termination = operation.awaitTermination();
            // 其内部元数据截止时间可能中断当前工作线程。可选凭据校验超时不得
            // 改变普通 ETL 语义；用户停止仍记录在运行独立的持久化停止标志中。
            if (!interruptedBefore && operation.deadlineExpired()) {
                Thread.interrupted();
            }
        }
        if (identity == null || termination != QueryRuntime.Termination.CONFIRMED) {
            throw new Unproven();
        }
        return identity;
    }

    /**
     * 计算输入内容摘要。
     *
     * @param value 待处理的值
     * @return 判断结果
     */
    private static boolean hash(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }
}
