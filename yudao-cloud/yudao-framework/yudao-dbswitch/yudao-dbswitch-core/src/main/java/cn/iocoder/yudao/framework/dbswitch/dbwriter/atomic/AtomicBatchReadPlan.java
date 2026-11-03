package cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic;

import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectPlan;

import java.util.List;
import java.util.Objects;

/**
 * 组件生成的有界查询及值参数。调用方必须先应用自身的对象、列、
 * 行授权及脱敏，再通过现有数据库查询 API 执行。
 * 回执是提交依据，不保证外部写入器之后不能改变行。
 */
public record AtomicBatchReadPlan(RestrictedSelectPlan select, List<Object> parameters, AtomicBatchReceipt receipt,
                                  List<String> fields, long afterRow, int limit,
                                  java.util.Set<String> allowedStatuses) {

    /**
     * 校验构造参数并初始化 {@code AtomicBatchReadPlan}。
     */
    public AtomicBatchReadPlan {
        Objects.requireNonNull(select);
        Objects.requireNonNull(receipt);
        parameters = List.copyOf(parameters);
        fields = AtomicBatchRequest.checkedFields(fields);
        allowedStatuses = java.util.Set.copyOf(allowedStatuses);
        if (afterRow < 0 || limit < 1 || limit > 1000 || !receipt.fields().containsAll(fields)
                || !java.util.Set.of("PASS", "WARN", "REJECT").containsAll(allowedStatuses)) {
            throw new IllegalArgumentException("Invalid batch read bounds");
        }
        var expected = new java.util.ArrayList<Object>();
        expected.add(receipt.key().batchKey());
        expected.add(afterRow);
        expected.addAll(allowedStatuses.stream().sorted().toList());
        if (!expected.equals(parameters)) {
            throw new IllegalArgumentException("Batch read parameters differ from bounds");
        }
    }

    /**
     * 供接受无绑定值 SQL 的现有授权 API 使用。只有组件生成的
     * SHA-256 批次键和非负 long 游标，才由 PostgreSQL AST 渲染器转为字面量。
     * 其他不透明键必须使用参数化 API。不提供通用客户端 SQL 替换。
     */
    public String authorizationSql() {
        if (parameters.size() < 2 || parameters.size() > 5 || !(parameters.get(0) instanceof String batch)
                || !batch.matches("[a-f0-9]{64}") || !batch.equals(receipt.key().batchKey())
                || !(parameters.get(1) instanceof Long cursor) || cursor < 0) {
            throw new IllegalArgumentException(
                    "Literal authorization plans require a SHA-256 batch key and bounded cursor");
        }
        for (int i = 2; i < parameters.size(); i++) {
            if (!(parameters.get(i) instanceof String status) || !java.util.Set.of("PASS", "WARN", "REJECT")
                    .contains(status)) {
                throw new IllegalArgumentException("Unsupported batch status literal");
            }
        }
        var statements = com.alibaba.druid.sql.SQLUtils.parseStatements(select.getNormalizedSql(),
                com.alibaba.druid.DbType.postgresql);
        String sql = com.alibaba.druid.sql.SQLUtils.toSQLString(statements, com.alibaba.druid.DbType.postgresql,
                parameters);
        var checked = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.parseBound(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL, sql,
                receipt.target().schema());
        if (!receipt.target().schema().equals(checked.getSchemaName()) || !receipt.target().table()
                .equals(checked.getTableName())) {
            throw new IllegalArgumentException("Batch read plan target changed");
        }
        return checked.getNormalizedSql();
    }
}
