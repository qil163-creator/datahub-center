package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.connection.EtlTableReference;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowOriginCapture;
import cn.iocoder.yudao.framework.etl.core.compile.EtlTableReadConfig;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlDataSourceUtils;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 读取数据库表节点（toolCode: read-db）
 *
 * 配置：databaseId 数据源；ifSql 0-整表 1-自定义 SQL；tableName / sql；fetchSize 批量提示值
 *
 * @author Li
 */
public class ReadDbExecutor extends AbstractEtlNodeExecutor {

    private EtlRowOriginCapture origins;

    public static final String TOOL_CODE = "read-db";

    /**
     * 初始化 {@code ReadDbExecutor}及其父类状态。
     */
    public ReadDbExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        EtlTableReadConfig.requireSupportedExecution(config());
        if (!config().getStr("whereCondition", "").isBlank() || config().get("selectedColumns") != null
                && !cn.hutool.json.JSONUtil.isNull(config().get("selectedColumns")))
            throw new IllegalArgumentException("轻量表输入尚不支持附加WHERE条件或属性字段选择，请使用已支持这些配置的DataX或原生表执行路径");
        Long databaseId = config().getLong("databaseId");
        if (databaseId == null) {
            throw new IllegalStateException(String.format("节点【%s】缺少配置：数据源", node.getName()));
        }
        boolean sqlMode = config().getInt("ifSql", 0) == 1;
        int fetchSize = config().getInt("fetchSize", 5000);
        if (fetchSize < 1) {
            throw new IllegalArgumentException("读取批次行数必须大于零");
        }
        fetchSize = Math.min(fetchSize, context.getDatabaseBatchLimits().maxRows());
        EtlConnectionInfo conn = context.getConnectionResolver().resolve(databaseId);
        logLine(String.format("连接数据源【%s】", conn.getName()));
        try (HikariDataSource dataSource = EtlDataSourceUtils.create(conn, context.getDatabaseRuntime())) {
            IDatabaseOperator operator = context.getDatabaseRuntime().reader(
                    dataSource, conn.getProductType());
            operator.setFetchSize(fetchSize);
            String sql;
            if (sqlMode) {
                sql = requireConfig("sql", "查询 SQL");
                logLine("按自定义 SQL 读取");
            } else {
                String tableName = requireConfig("tableName", "表名");
                logLine(String.format("按整表读取：%s", tableName));
                sql = "SELECT * FROM " + EtlTableReference.resolve(conn, tableName, config().getStr("schemaName"))
                        .quoted();
            }
            var policy = context.getReadPolicyPort();
            String authorizedSql = policy == null ? sql : policy.rewrite(node.getNo(), databaseId, sql);
            if (StrUtil.isBlank(authorizedSql)) {
                throw new IllegalStateException("读取策略未返回授权 SQL");
            }
            if (!sqlMode && context.getLimits() != null && context.getLimits().isCaptureSourceOrigins()
                    && context.getDag().getAllDataDownstreams(node.getNo()).stream()
                    .anyMatch(id -> "quality-check".equals(context.getDag().getNode(id).getToolCode())
                            && context.getDag().getDataUpstreamList(id).equals(java.util.List.of(node.getNo())))) {
                origins = EtlRowOriginCapture.prepare(context.getDatabaseRuntime(), conn, node.getNo(), sql,
                        authorizedSql);
            }
            StatementResultSet srs = operator.queryTableData(authorizedSql);
            try (srs; ResultSet rs = srs.getResultset()) {
                if (policy != null) {
                    policy.validateReadConnection(node.getNo(), databaseId, authorizedSql,
                            srs.getConnection(),
                            cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort.ReadConnectionStage.OPENED);
                }
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                String[] labels = new String[columnCount];
                for (int i = 1; i <= columnCount; i++) {
                    labels[i - 1] = meta.getColumnLabel(i);
                }
                if (new java.util.HashSet<>(java.util.Arrays.asList(labels)).size() != columnCount) {
                    throw new IllegalArgumentException("读取结果包含重复列名，请设置唯一别名");
                }
                List<Map<String, Object>> batch = new ArrayList<>();
                long batchBytes = 0;
                long scannedBytes = 0;
                int batchRows = Math.min(500, context.getDatabaseBatchLimits().maxRows());
                long maxBatchBytes = context.getDatabaseBatchLimits().maxBatchBytes();
                while (rs.next()) {
                    checkStop();
                    Map<String, Object> row = new LinkedHashMap<>(columnCount);
                    Object[] values = new Object[columnCount];
                    for (int i = 1; i <= columnCount; i++) {
                        values[i - 1] = rs.getObject(i);
                    }
                    // 在源行和连接仍有效时，物化并释放定位器。
                    values = BatchValueConverter.convert(java.util.List.<Object[]>of(values), columnCount,
                            context.getDatabaseBatchLimits()).getFirst();
                    for (int i = 0; i < columnCount; i++) {
                        row.put(labels[i], values[i]);
                    }
                    readCount.incrementAndGet();
                    long bytes = EtlRunContext.estimateRowBytes(row);
                    scannedBytes = Math.addExact(scannedBytes, bytes);
                    if (bytes > maxBatchBytes || (context.getLimits() != null &&
                            (bytes > context.getLimits().getMaxRowBytes() || readCount.get() > context.getLimits()
                                    .getMaxRows()
                                    || scannedBytes > context.getLimits().getMaxBytes()))) {
                        throw new IllegalStateException("数据库输入超过轻量 ETL 的读取行数或字节预算");
                    }
                    if (!batch.isEmpty() && (batch.size() >= batchRows || bytes > maxBatchBytes - batchBytes)) {
                        emitAuthorized(databaseId, authorizedSql, sql, batch);
                        batch = new ArrayList<>();
                        batchBytes = 0;
                    }
                    batch.add(row);
                    batchBytes = Math.addExact(batchBytes, bytes);
                }
                if (!batch.isEmpty()) {
                    emitAuthorized(databaseId, authorizedSql, sql, batch);
                }
                if (policy != null) {
                    policy.validateReadConnection(node.getNo(), databaseId, authorizedSql,
                            srs.getConnection(),
                            cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort.ReadConnectionStage.DRAINED);
                }
            }
        }
        logLine(String.format("读取完成，读取 %s 行，授权输出 %s 行", readCount.get(), writeCount.get()));
    }

    /**
     * 生成已授权。
     */
    private void emitAuthorized(Long databaseId, String sql, String originalSql,
            List<Map<String, Object>> rows) throws InterruptedException {
        checkStop();
        int inputRows = rows.size();
        var raw = origins == null ? null : origins.snapshot(rows);
        var policy = context.getReadPolicyPort();
        List<Map<String, Object>> output = policy == null ? rows : policy.filter(node.getNo(), databaseId, sql,
                List.copyOf(rows), originalSql);
        if (output == null || output.size() > inputRows) {
            throw new IllegalStateException("读取策略返回无效数据批次");
        }
        for (int index = 0; index < output.size(); index++) {
            var row = output.get(index);
            if (row == null) {
                throw new IllegalStateException("读取策略返回空记录");
            }
            checkStop();
            pushRow(row, cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef.PORT_MAIN,
                    origins == null || output.size() != inputRows ? null : origins.origin(raw.get(index), row));
        }
    }

}
