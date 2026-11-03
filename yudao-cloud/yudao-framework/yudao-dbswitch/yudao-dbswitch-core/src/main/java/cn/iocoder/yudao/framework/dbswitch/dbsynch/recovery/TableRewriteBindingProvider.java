package cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery;

/** 仅在原生发现关闭其目标连接后调用。领域代码在短控制面事务中固定逻辑
 * 资源及实际原生标识，绝不重新实现元数据机制。 */
@FunctionalInterface
public interface TableRewriteBindingProvider {

    /** 显式产品管理权限，在原生标识探测可能修改缓存前检查。 */
    default void authorizeManagementProbe(String schema, String table, String logicalResource) {
        throw new UnsupportedOperationException(
                "Native management identity probing requires trusted deployment admission");
    }

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    TableRewriteBinding resolve(String schema, String table, String logicalResource,
            TableRewriteIdentity identity, String structureFingerprint);
}
