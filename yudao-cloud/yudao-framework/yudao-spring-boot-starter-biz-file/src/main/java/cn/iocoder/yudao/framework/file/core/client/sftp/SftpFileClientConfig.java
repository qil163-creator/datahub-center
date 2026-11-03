package cn.iocoder.yudao.framework.file.core.client.sftp;

import cn.iocoder.yudao.framework.file.core.client.FileClientConfig;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;
import org.hibernate.validator.constraints.URL;

/**
 * Sftp 文件客户端的配置类
 *
 * @author 芋道源码
 */
@Data
public class SftpFileClientConfig implements FileClientConfig {

    /**
     * 基础路径
     */
    @NotEmpty(message = "基础路径不能为空")
    private String basePath;

    /**
     * 自定义域名
     */
    @NotEmpty(message = "domain 不能为空")
    @URL(message = "domain 必须是 URL 格式")
    private String domain;

    /**
     * 主机地址
     */
    @NotEmpty(message = "host 不能为空")
    private String host;
    /**
     * 主机端口
     */
    @NotNull(message = "port 不能为空")
    private Integer port;
    /**
     * 用户名
     */
    @NotEmpty(message = "用户名不能为空")
    private String username;
    /**
     * 密码
     */
    @NotEmpty(message = "密码不能为空")
    @ToString.Exclude
    private String password;

    /** 通过可信渠道取得的 OpenSSH SHA-256 主机公钥指纹。 */
    @jakarta.validation.constraints.Pattern(regexp = "^SHA256:[A-Za-z0-9+/]{43}$", message = "服务器公钥指纹必须是 OpenSSH SHA-256 格式")
    @NotEmpty(message = "服务器公钥指纹不能为空")
    private String hostKeyFingerprint;
}

