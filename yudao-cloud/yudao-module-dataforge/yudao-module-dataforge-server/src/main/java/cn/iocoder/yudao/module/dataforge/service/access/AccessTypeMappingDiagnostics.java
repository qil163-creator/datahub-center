package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TableTypeMappingPlan;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.AccessTypeMappingPlanRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessTaskDetailRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDetailDO;

/** 组件元数据决策到领域诊断的薄转换；不保留 SQL、驱动错误、连接材料或行值。 */
public final class AccessTypeMappingDiagnostics {

    private static final int MAX_JSON_LENGTH = 1_000_000;

    /**
     * 限制访问类型映射诊断信息工具的实例化，统一通过静态能力使用。
     */
    private AccessTypeMappingDiagnostics() {
    }

    /**
     * 将输入转换为访问类型映射计划。
     *
     * @param plan 计划
     * @param sourceSchema 来源结构
     * @param sourceTable 来源表
     * @param targetSchema 目标结构
     * @param targetTable 目标表
     * @return 访问类型映射计划
     */
    public static AccessTypeMappingPlan from(TableTypeMappingPlan plan,
            String sourceSchema, String sourceTable, String targetSchema, String targetTable) {
        if (plan == null) {
            return null;
        }
        var visible = plan.columns().size() > 4096 ? plan.columns().stream().sorted(
                java.util.Comparator.comparingInt(
                        (cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.ColumnTypeMapping column) ->
                                severity(column.fidelity().name())).reversed()).limit(4096).toList() : plan.columns();
        var columns = visible.stream().map(column -> new AccessTypeMappingPlan.Column(
                bounded(column.column(), 256), bounded(column.sourceNativeType(), 1024),
                bounded(column.logicalType(), 1024),
                bounded(column.targetNativeType(), 1024), column.fidelity().name(),
                column.fidelity() == TypeFidelity.EXACT || plan.acceptedLossyColumns().contains(column.column()),
                column.fingerprint(), column.lossCodes().stream().sorted().toList(),
                column.issues().stream().limit(128).map(issue -> new AccessTypeMappingPlan.Issue(
                        issue.severity().name(), issue.code(), bounded(issue.detail(), 2048))).toList())).toList();
        boolean truncated = longValue(sourceSchema, 128) || longValue(sourceTable, 256)
                || longValue(targetSchema, 128) || longValue(targetTable, 256)
                || plan.columns().size() > 4096 || plan.columns().stream().anyMatch(column ->
                longValue(column.column(), 256) || longValue(column.sourceNativeType(), 1024)
                        || longValue(column.logicalType(), 1024) || longValue(column.targetNativeType(), 1024)
                        || column.issues().size() > 128 || column.issues().stream()
                        .anyMatch(issue -> longValue(issue.detail(), 2048)));
        return boundedPlan(new AccessTypeMappingPlan(1,
                plan.columns().isEmpty() ? null : plan.columns().getFirst().sourceProduct().name(),
                bounded(sourceSchema, 128), bounded(sourceTable, 256), plan.targetProduct().name(),
                bounded(targetSchema, 128), bounded(targetTable, 256), plan.fidelity().name(), plan.accepted(),
                plan.columns().size(), truncated, columns));
    }

    /**
     * 写入访问类型映射诊断信息数据。
     *
     * @param plan 计划
     * @return 写入访问类型映射诊断信息数据文本
     */
    public static String write(AccessTypeMappingPlan plan) {
        if (plan == null) {
            return null;
        }
        return JsonUtils.toJsonString(boundedPlan(plan));
    }

    /**
     * 组装访问类型映射诊断信息响应数据。
     *
     * @param plan 计划
     * @return 访问类型映射计划响应数据
     */
    public static AccessTypeMappingPlanRespVO response(AccessTypeMappingPlan plan) {
        if (plan == null) {
            return null;
        }
        var response = new AccessTypeMappingPlanRespVO();
        response.setSchemaVersion(plan.schemaVersion());
        response.setSourceProduct(plan.sourceProduct());
        response.setSourceSchema(plan.sourceSchema());
        response.setSourceTable(plan.sourceTable());
        response.setTargetProduct(plan.targetProduct());
        response.setTargetSchema(plan.targetSchema());
        response.setTargetTable(plan.targetTable());
        response.setFidelity(plan.fidelity());
        response.setAccepted(plan.accepted());
        response.setColumns(plan.columns());
        response.setTotalColumns(plan.totalColumns());
        response.setTruncated(plan.truncated());
        return response;
    }

    /**
     * 读取响应。
     *
     * @param json JSON 内容
     * @return 访问类型映射计划响应数据
     */
    public static AccessTypeMappingPlanRespVO readResponse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        if (json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("类型映射诊断超过读取上限");
        }
        return response(JsonUtils.parseObject(json, AccessTypeMappingPlan.class));
    }

    /** JSON 诊断不是同形 String 字段，必须显式转换，避免 Bean 转换器将字符串隐式当成 VO。 */
    public static DataAccessTaskDetailRespVO detailResponse(DataAccessTaskDetailDO detail) {
        var response = new DataAccessTaskDetailRespVO();
        response.setId(detail.getId());
        response.setDataAccessId(detail.getDataAccessId());
        response.setTaskId(detail.getTaskId());
        response.setSourceSchemaName(detail.getSourceSchemaName());
        response.setSourceTableName(detail.getSourceTableName());
        response.setTargetSchemaName(detail.getTargetSchemaName());
        response.setTargetTableName(detail.getTargetTableName());
        response.setSyncCount(detail.getSyncCount());
        response.setSyncBytes(detail.getSyncBytes());
        response.setIfSuccess(detail.getIfSuccess());
        response.setErrorMsg(detail.getErrorMsg());
        response.setSuccessMsg(detail.getSuccessMsg());
        response.setCreateTime(detail.getCreateTime());
        response.setTypeMappingPlan(readResponse(detail.getTypeMappingPlan()));
        return response;
    }

    /**
     * 获取访问类型映射诊断信息的有界。
     *
     * @param value 待处理的值
     * @param max 最大值
     * @return 访问类型映射诊断信息的有界文本
     */
    private static String bounded(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 判断长整数值条件是否成立。
     *
     * @param value 待处理的值
     * @param max 最大值
     * @return 处理是否成功
     */
    private static boolean longValue(String value, int max) {
        return value != null && value.length() > max;
    }

    /**
     * 获取访问类型映射诊断信息的有界计划。
     *
     * @param plan 计划
     * @return 访问类型映射计划
     */
    private static AccessTypeMappingPlan boundedPlan(AccessTypeMappingPlan plan) {
        while (JsonUtils.toJsonString(plan).length() > MAX_JSON_LENGTH) {
            if (plan.columns().isEmpty()) {
                throw new IllegalArgumentException("类型映射诊断上下文超过上限");
            }
            var prioritized = plan.columns().stream().sorted(java.util.Comparator.comparingInt(
                    (AccessTypeMappingPlan.Column column) -> severity(column.fidelity())).reversed()).toList();
            plan = new AccessTypeMappingPlan(plan.schemaVersion(), plan.sourceProduct(), plan.sourceSchema(),
                    plan.sourceTable(),
                    plan.targetProduct(), plan.targetSchema(), plan.targetTable(), plan.fidelity(), plan.accepted(),
                    plan.totalColumns(), true, prioritized.subList(0, prioritized.size() / 2));
        }
        return plan;
    }

    /**
     * 获取访问类型映射诊断信息的严重程度。
     *
     * @param fidelity 保真
     * @return 严重程度数值
     */
    private static int severity(String fidelity) {
        return "UNSUPPORTED".equals(fidelity) ? 2 : "LOSSY".equals(fidelity) ? 1 : 0;
    }
}
