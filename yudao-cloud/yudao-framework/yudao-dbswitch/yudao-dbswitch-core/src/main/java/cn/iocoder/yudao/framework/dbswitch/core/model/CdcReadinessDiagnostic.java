package cn.iocoder.yudao.framework.dbswitch.core.model;

/** 仅证明已读取的日志配置及账号角色前置条件，不证明复制槽、网络认证或目标表复制权限。 */
public record CdcReadinessDiagnostic(boolean logConfigurationReady, boolean accountPrivilegeReady) {

}
