package cn.iocoder.yudao.module.supply.api.app.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.TreeSet;

/**
 * 来源级授权命令 v2。来源系统必须与经过认证的调用服务一致。
 * 构造时冻结、校验载荷，使重试与本地调用同样遵守 HTTP 的边界。
 */
public record SupplyGrantSourceCommandDTO(
        int protocolVersion,
        @NotBlank @Size(max = 128) String operationId,
        @NotBlank @Size(max = 64) String sourceSystem,
        @NotBlank @Size(max = 256) String sourceKey,
        @Positive long sourceRevision,
        @NotNull @Positive Long appId,
        @NotNull @Positive Long apiId,
        @NotNull Action action,
        @Valid Scope scope,
        @Size(max = 2000) String reason) {

    /**
     * 创建供应授权来源命令实例。
     *
     * @param protocolVersion 协议版本
     * @param operationId 操作编号
     * @param sourceSystem 来源系统
     * @param sourceKey 来源键
     * @param sourceRevision 来源修订号
     * @param appId 应用编号
     * @param apiId API 编号
     * @param action 待执行的操作
     * @param scope 范围
     * @param reason 操作原因
     */
    public SupplyGrantSourceCommandDTO {
        require(protocolVersion == 2, "protocolVersion must be 2");
        operationId = text(operationId, 128, "operationId");
        sourceSystem = text(sourceSystem, 64, "sourceSystem");
        sourceKey = text(sourceKey, 256, "sourceKey");
        require(sourceRevision > 0, "sourceRevision must be positive");
        positive(appId, "appId");
        positive(apiId, "apiId");
        require(action != null, "action is required");
        require(action != Action.GRANT || scope != null, "GRANT requires approved scope");
        if (reason != null) {
            reason = reason.strip();
            require(reason.length() <= 2000, "reason is too long");
        }
    }

    public enum Action {GRANT, SUSPEND, REVOKE}

    /** 物理标识符保留元数据中的大小写和空格，不进行 SQL 文本解析。 */
    public record Binding(@NotNull @Positive Long databaseId,
                          @NotBlank @Size(max = 256) String schemaName,
                          @NotBlank @Size(max = 256) String tableName) {

        /**
         * 创建绑定实例。
         *
         * @param databaseId 数据源编号
         * @param schemaName schema 名称
         * @param tableName 表名
         */
        public Binding {
            positive(databaseId, "databaseId");
            identifier(schemaName, "schemaName");
            identifier(tableName, "tableName");
        }
    }

    public record Scope(
            String approvalRef, String sourceType, Long permissionUserId,
            @Valid Binding binding, String approvedContractHash, String apiVersion,
            String purpose, String dutyMatter, String policyMode,
            List<String> approvedColumns, boolean forever,
            Long validFromMillis, Long validToMillis, Quota quota) {

        /**
         * 创建范围实例。
         *
         * @param approvalRef 审批引用
         * @param sourceType 来源类型
         * @param permissionUserId 权限主体用户编号
         * @param binding 绑定
         * @param approvedContractHash 已批准契约摘要
         * @param apiVersion API版本
         * @param purpose 用途
         * @param dutyMatter 职责事项
         * @param policyMode 策略模式
         * @param approvedColumns 已批准列集合
         * @param forever 永久
         * @param validFromMillis 有效来源毫秒数
         * @param validToMillis 有效目标毫秒数
         */
        public Scope(String approvalRef, String sourceType, Long permissionUserId, Binding binding,
                String approvedContractHash, String apiVersion, String purpose, String dutyMatter,
                String policyMode, List<String> approvedColumns, boolean forever, Long validFromMillis,
                Long validToMillis) {
            this(approvalRef, sourceType, permissionUserId, binding, approvedContractHash, apiVersion, purpose,
                    dutyMatter, policyMode, approvedColumns, forever, validFromMillis, validToMillis, null);
        }

        /**
         * 创建范围实例。
         *
         * @param approvalRef 审批引用
         * @param sourceType 来源类型
         * @param permissionUserId 权限主体用户编号
         * @param binding 绑定
         * @param approvedContractHash 已批准契约摘要
         * @param apiVersion API版本
         * @param purpose 用途
         * @param dutyMatter 职责事项
         * @param policyMode 策略模式
         * @param approvedColumns 已批准列集合
         * @param forever 永久
         * @param validFromMillis 有效来源毫秒数
         * @param validToMillis 有效目标毫秒数
         * @param quota 额度
         */
        public Scope {
            approvalRef = text(approvalRef, 256, "approvalRef");
            sourceType = text(sourceType, 64, "sourceType");
            positive(permissionUserId, "permissionUserId");
            require(binding != null, "binding is required");
            approvedContractHash = text(approvedContractHash, 128, "approvedContractHash");
            apiVersion = text(apiVersion, 128, "apiVersion");
            purpose = text(purpose, 2000, "purpose");
            dutyMatter = text(dutyMatter, 2000, "dutyMatter");
            policyMode = text(policyMode, 64, "policyMode");
            require(approvedColumns != null && !approvedColumns.isEmpty() && approvedColumns.size() <= 2048,
                    "approvedColumns requires 1 to 2048 physical fields");
            TreeSet<String> fields = new TreeSet<>();
            for (String field : approvedColumns) {
                identifier(field, "approvedColumn");
                require(!"*".equals(field), "wildcard is not a frozen physical field");
                fields.add(field);
            }
            approvedColumns = List.copyOf(fields);
            require(validFromMillis != null, "validFromMillis is required");
            if (forever) {
                require(validToMillis == null, "forever scope cannot have validToMillis");
            } else {
                require(validToMillis != null && validToMillis > validFromMillis,
                        "scope requires a nonempty half-open interval");
            }
        }
    }

    /** 批准来源终身累计额度；空表示无限制。次数计成功返回，行数计实际披露行。 */
    public record Quota(String metric, long limit) {

        /**
         * 创建额度实例。
         *
         * @param metric 指标
         * @param limit 读取或处理上限
         */
        public Quota {
            require(java.util.Set.of("CALLS", "ROWS").contains(metric), "invalid quota metric");
            require(limit > 0 && limit <= Integer.MAX_VALUE, "quota limit must be a positive integer");
        }
    }

    /**
     * 获取供应授权来源命令的文本。
     *
     * @param value 待处理的值
     * @param max 最大值
     * @param name 名称
     * @return 供应授权来源命令的文本文本
     */
    private static String text(String value, int max, String name) {
        require(value != null && !value.isBlank() && value.length() <= max, name + " is missing or too long");
        return value.strip();
    }

    /**
     * 校验物理标识符非空、长度受限且不包含空字符。
     *
     * @param value 待处理的值
     * @param name 名称
     */
    private static void identifier(String value, String name) {
        require(value != null && !value.isBlank() && value.length() <= 256 && value.indexOf('\0') < 0,
                name + " must be a physical identifier");
    }

    /**
     * 校验输入编号为非空正整数。
     *
     * @param value 待处理的值
     * @param name 名称
     */
    private static void positive(Long value, String name) {
        require(value != null && value > 0, name + " must be positive");
    }

    /**
     * 校验供应授权来源命令所需条件，不满足时抛出配置错误。
     *
     * @param condition 条件
     * @param message 消息
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
