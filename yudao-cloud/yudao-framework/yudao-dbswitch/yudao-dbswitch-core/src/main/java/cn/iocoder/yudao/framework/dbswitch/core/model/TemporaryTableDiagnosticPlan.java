package cn.iocoder.yudao.framework.dbswitch.core.model;

/** 产品生成的会话临时表计划，列固定为 probe_id/probe_value；不接受调用方 SQL。 */
public record TemporaryTableDiagnosticPlan(String createSql, String tableReference,
                                           String alterSql, String dropSql, String upsertSql, String mergeSql,
                                           int mergeMinimumMajorVersion, String truncateSql, String renameSql,
                                           String renamedTableReference, String dropRenamedSql,
                                           String beginSql, String rollbackSql, boolean transactionSupported,
                                           boolean unsupportedOperationsNotApplicable) {

    /** 旧产品继续使用 JDBC 事务，缺少 SQL 表示尚未实现；非正最低版本表示已由原生连接校验把关。 */
    public TemporaryTableDiagnosticPlan(String createSql, String tableReference,
            String alterSql, String dropSql, String upsertSql, String mergeSql,
            int mergeMinimumMajorVersion, String truncateSql, String renameSql,
            String renamedTableReference, String dropRenamedSql) {
        this(createSql, tableReference, alterSql, dropSql, upsertSql, mergeSql, mergeMinimumMajorVersion,
                truncateSql, renameSql, renamedTableReference, dropRenamedSql, null, null, true, false);
    }

    /** 原生事务必须成对定义，禁止只有 BEGIN 却没有可执行的回滚。 */
    public TemporaryTableDiagnosticPlan {
        if ((beginSql == null) != (rollbackSql == null)
                || (beginSql != null && (beginSql.isBlank() || rollbackSql.isBlank() || !transactionSupported))) {
            throw new IllegalArgumentException("原生事务必须同时提供有效的开始和回滚语句");
        }
    }

    /**
     * 使用给定参数初始化 {@code TemporaryTableDiagnosticPlan}。
     */
    public TemporaryTableDiagnosticPlan(String createSql, String tableReference, String alterSql, String dropSql) {
        this(createSql, tableReference, alterSql, dropSql, null, null, Integer.MAX_VALUE, null, null, null, null);
    }
}
