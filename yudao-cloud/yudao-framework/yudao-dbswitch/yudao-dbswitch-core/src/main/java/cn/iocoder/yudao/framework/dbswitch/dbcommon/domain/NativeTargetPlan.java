package cn.iocoder.yudao.framework.dbswitch.dbcommon.domain;

/** 产品持有的预检查结果。执行在破坏性操作前重新检查目标标识。
 * 计划仅可使用一次；外部原生 DDL 不属于 JDBC 事务，可能留下未知结果。 */
public interface NativeTargetPlan {

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    String digest();

    /**
     * 说明当前目标计划是否会替换已有对象。
     */
    boolean replacesExisting();

    /** 原生 DDL 无法原子比较目标代际时，供技术调用方使用的契约。 */
    default boolean requiresExclusiveDdlOwnership() {
        return false;
    }

    /** 执行前后快照，用于 UNKNOWN 对账；缺少标识不代表资源不存在。 */
    default java.util.List<NativeRecoveryResource> recoveryResources() {
        return java.util.List.of();
    }

    /** 在 DDL、清空或写入前校验数据协议；仅结构调用方省略此步骤。 */
    default void preflightDataTransfer() {
    }

    /**
     * 重新核对目标状态并应用当前原生目标计划。
     */
    void execute();
}
