package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

/** 原生发现后的可信运行时回调，包括源关系族展开。
 * 可在短事务中持久化或解析控制面固定信息，绝不执行目标 DML/DDL。
 * 返回的物理固定信息必须是已准入的历史代际，不得在恢复时静默刷新。 */
@FunctionalInterface
public interface GroupRewriteBindingProvider {

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    GroupRewriteBinding resolve(String schema, String table, String logicalResource,
            String physicalFingerprint, String structureFingerprint);
}
