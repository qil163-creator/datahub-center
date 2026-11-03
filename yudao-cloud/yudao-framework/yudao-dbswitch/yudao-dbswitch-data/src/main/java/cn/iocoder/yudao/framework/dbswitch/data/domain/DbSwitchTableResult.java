package cn.iocoder.yudao.framework.dbswitch.data.domain;

import lombok.Data;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @ClassName DbSwitchTable
 * @Author zrx
 * @Date 2022/10/28 9:37
 */
@Data
public class DbSwitchTableResult {

    private String sourceSchemaName;

    private String sourceTableName;

    private String targetSchemaName;

    private String targetTableName;

    private String tableRemarks;

    private Date syncTime;

    private AtomicLong syncCount = new AtomicLong(0);

    private AtomicLong syncBytes = new AtomicLong(0);

    private AtomicBoolean ifSuccess = new AtomicBoolean(true);

    private String errorMsg;

    private String successMsg = "同步成功";

    /** 仅基于元数据的跨产品决策；异步执行后仍保留被拒绝的列及有损原因。 */
    private cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TableTypeMappingPlan typeMappingPlan;

    /** 实际全量流水线事实；null 表示旧执行器或尚未采集的执行路径，不能从 ifSuccess 推导。 */
    private TableTransferSummary transferSummary;

    /** 有界的全量复制进度，包含失败结果；不收集这些事实的路径返回 null。 */
    private volatile TableBatchProgress batchProgress;

    /** 冻结的原生 DDL 计划标识，在任何原生修改前获取。 */
    private String nativePlanDigest;

    /** 保留最后观测到的原生 DDL 资源，即使执行结果为 UNKNOWN。 */
    private volatile java.util.List<cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeRecoveryResource> nativeRecoveryResources = java.util.List.of();

    /**
     * 设置原生恢复资源集合。
     */
    public void setNativeRecoveryResources(
            java.util.List<cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeRecoveryResource> resources) {
        nativeRecoveryResources = java.util.List.copyOf(java.util.Objects.requireNonNull(resources));
    }
}
