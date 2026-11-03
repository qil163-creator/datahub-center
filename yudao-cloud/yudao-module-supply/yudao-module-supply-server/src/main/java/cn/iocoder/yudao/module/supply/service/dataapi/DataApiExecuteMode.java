package cn.iocoder.yudao.module.supply.service.dataapi;

/**
 * 服务端确定的执行场景，避免调用方组合多个布尔参数。
 */
public enum DataApiExecuteMode {

    /** 向导/草稿只读预览：连接前拒绝写入、转发和多语句，不读正式结果缓存。 */
    DRAFT_READONLY_PREVIEW,
    /** 管理端对已上线配置的开发执行；首批只读。 */
    ADMIN_EXECUTE,
    /** 部门应用经开放网关调用。 */
    APP_GATEWAY;

    /**
     * 判断当前执行模式是否为只读模式。
     *
     * @return 处理是否成功
     */
    public boolean readOnly() {
        return this != APP_GATEWAY;
    }

    /**
     * 检查运行时门禁。
     *
     * @return 处理是否成功
     */
    public boolean checkRuntimeGuard() {
        return this == APP_GATEWAY || this == ADMIN_EXECUTE;
    }
}
