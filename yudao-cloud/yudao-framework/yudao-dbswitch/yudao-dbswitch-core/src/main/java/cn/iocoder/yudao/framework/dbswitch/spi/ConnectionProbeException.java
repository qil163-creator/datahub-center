package cn.iocoder.yudao.framework.dbswitch.spi;

import java.util.Objects;

/** 产品探测器只上报稳定失败类别；厂商异常内容不进入面向用户的诊断。 */
public final class ConnectionProbeException extends Exception {

    public enum Reason {
        AUTHENTICATION("AUTHENTICATION_FAILED：认证失败，请检查账号、密码或令牌是否有效"),
        AUTHORIZATION("AUTHORIZATION_FAILED：账号无权访问目标资源，请检查资源授权"),
        TARGET_NOT_FOUND("TARGET_NOT_FOUND：目标数据库或资源不存在，请检查名称及服务地址"),
        TLS("TLS_VERIFICATION_FAILED：TLS 证书或主机名验证失败，请检查证书信任链和服务域名"),
        CONFIGURATION("INVALID_CONFIGURATION：连接配置不完整或不合法");

        private final String diagnosticMessage;

        /**
         * 初始化 {@code Reason}。
         */
        Reason(String diagnosticMessage) {
            this.diagnosticMessage = diagnosticMessage;
        }

        /**
         * 返回诊断消息。
         */
        public String diagnosticMessage() {
            return diagnosticMessage;
        }
    }

    private final Reason reason;

    /**
     * 校验构造参数并初始化 {@code ConnectionProbeException}及其父类状态。
     */
    public ConnectionProbeException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason).diagnosticMessage(), cause);
        this.reason = reason;
    }

    /**
     * 返回原因。
     */
    public Reason reason() {
        return reason;
    }
}
