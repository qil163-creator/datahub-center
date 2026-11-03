package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.data.query.OperationBudget;
import cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget;
import cn.iocoder.yudao.framework.etl.core.connection.EtlTableReference;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 仅用于预览的读取器。绝不回退到全量运行读取器，也不接受客户端 SQL。 */
final class EtlPreviewReadDbExecutor extends AbstractEtlNodeExecutor {

    private final EtlPreviewEngine.DatabaseSource source;

    private final int limit;

    private final AtomicBoolean truncated;

    private final AtomicLong sourceBytes;

    private final cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl control;

    /**
     * 初始化 {@code EtlPreviewReadDbExecutor}及其父类状态。
     */
    EtlPreviewReadDbExecutor(EtlNodeDef node, EtlRunContext context,
            EtlPreviewEngine.DatabaseSource source, int limit,
            AtomicBoolean truncated, AtomicLong sourceBytes,
            cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl control) {
        super(node, context);
        this.source = source;
        this.limit = limit;
        this.truncated = truncated;
        this.sourceBytes = sourceBytes;
        this.control = control;
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        // 屏蔽厂商、配置或权限异常文本：其中可能含有 SQL、连接详情或数据值。
        try {
            readSample();
        } catch (Exception failure) {
            throw new IllegalStateException("数据库预览失败：请检查源权限、整表配置、读取限额或超时");
        }
    }

    /**
     * 读取样本。
     */
    private void readSample() throws Exception {
        if (config().getInt("ifSql", 0) != 0) {
            throw new IllegalArgumentException("SQL preview is disabled");
        }
        Long databaseId = config().getLong("databaseId");
        if (databaseId == null) {
            throw new IllegalArgumentException("Missing database");
        }
        var connection = source.connections().resolve(databaseId);
        if (connection == null || connection.getProductType() != ProductTypeEnum.POSTGRESQL) {
            throw new IllegalArgumentException("Only verified PostgreSQL preview is supported");
        }
        String sql = "SELECT * FROM " + EtlTableReference.resolve(connection,
                requireConfig("tableName", "表名"), config().getStr("schemaName")).quoted();
        var expected = RestrictedSelectGuard.parseBound(connection.getProductType(), sql, connection.getSchema());
        checkStop();
        String authorizedSql = source.policy().rewrite(node.getNo(), databaseId, sql);
        var authorized = RestrictedSelectGuard.parseBound(connection.getProductType(), authorizedSql,
                connection.getSchema());
        if (!Objects.equals(expected.getSchemaName(), authorized.getSchemaName())
                || !Objects.equals(expected.getTableName(), authorized.getTableName())) {
            throw new IllegalArgumentException("Read policy changed the physical table");
        }
        checkStop();
        int sampleSize = limit + 1;
        var budget = new OperationBudget(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(3),
                sampleSize, EtlPreviewEngine.MAX_BYTES, EtlPreviewEngine.MAX_ROW_BYTES, sampleSize);
        var operation = source.runtime().queries()
                .register(source.scope(), UUID.randomUUID().toString(), UUID.randomUUID(), budget);
        if (control != null) {
            control.register(() -> operation.requestCancel(source.scope()),
                    () -> operation.termination() == QueryRuntime.Termination.CONFIRMED);
        }
        List<Map<String, Object>> sample = new ArrayList<>();
        QueryRuntime.Termination termination;
        try {
            operation.bindWorker();
            var spec = new QueryRuntime.ConnectionSpec(connection.getProductType(), connection.getJdbcUrl(),
                    connection.getUsername(), connection.getPassword(), connection.getDriverProfileId(),
                    connection.getConfigurationRevision());
            try (var session = operation.openSession(spec, ConnectionBudget.Role.WORKLOAD);
                 var cursor = session.query(source.scope(), authorized.getNormalizedSql(), List.of())) {
                var page = cursor.fetch(source.scope(), cursor.id(), sampleSize);
                var labels = page.columns().stream().map(c -> c.getLabelName()).toList();
                if (new HashSet<>(labels).size() != labels.size()) {
                    throw new IllegalArgumentException("Duplicate column labels");
                }
                for (var values : page.rows()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < labels.size(); i++) {
                        row.put(labels.get(i), values.get(i));
                    }
                    long bytes = EtlRunContext.estimateRowBytes(row);
                    if (bytes > EtlPreviewEngine.MAX_ROW_BYTES || sourceBytes.addAndGet(
                            bytes) > EtlPreviewEngine.MAX_BYTES) {
                        throw new IllegalArgumentException("Preview source byte budget exceeded");
                    }
                    sample.add(row);
                }
            }
        } finally {
            operation.workerExited();
            termination = operation.awaitTermination();
            if (termination != QueryRuntime.Termination.CONFIRMED && control == null) {
                context.markTerminationUnconfirmed();
            }
        }
        if (termination != QueryRuntime.Termination.CONFIRMED) {
            throw new IllegalStateException("Source termination unconfirmed");
        }
        // 业务权限或脱敏回调期间不持有数据库连接或事务。
        checkStop();
        int sampled = sample.size();
        var safe = source.policy().filter(node.getNo(), databaseId, authorizedSql, List.copyOf(sample), sql);
        if (safe == null || safe.size() > sampled) {
            throw new IllegalArgumentException("Invalid read policy result");
        }
        if (sampled > limit) {
            truncated.set(true);
        }
        for (var row : safe) {
            if (row == null) {
                throw new IllegalArgumentException("Null policy row");
            }
            checkStop();
            readCount.incrementAndGet();
            pushRow(row);
        }
    }
}
