package cn.iocoder.yudao.framework.file.core.remote;

import java.util.Set;
import java.net.URI;

/** 仅承载协议配置，不持有业务身份；禁止将认证信息输出到日志。 */
public record RemoteFileConfig(Protocol protocol, String host, int port, String username,
        String password, String rootPath, String webHdfsUrl, Set<String> allowedDataNodeAuthorities, boolean ftpPassive) {
    public enum Protocol { FTP, HDFS }

    /** 默认使用 FTP 被动模式；显式主动模式用于有对应网络策略的存储配置。 */
    public RemoteFileConfig(Protocol protocol, String host, int port, String username, String password,
            String rootPath, String webHdfsUrl, Set<String> allowedDataNodeAuthorities) {
        this(protocol, host, port, username, password, rootPath, webHdfsUrl, allowedDataNodeAuthorities, true);
    }

    public RemoteFileConfig {
        if (protocol == null) throw new IllegalArgumentException("文件协议不能为空");
        rootPath = RemoteFilePaths.normalize(rootPath);
        allowedDataNodeAuthorities = allowedDataNodeAuthorities == null
                ? Set.of() : Set.copyOf(allowedDataNodeAuthorities);
        if (username != null && (username.length() > 255 || hasControl(username))) throw new IllegalArgumentException("文件认证用户名不合法");
        if (password != null && (password.length() > 4096 || hasControl(password))) throw new IllegalArgumentException("文件认证参数不合法");
        if (host != null && !host.isBlank()) validateHost(host);
        if (protocol == Protocol.FTP) {
            if (host == null || host.isBlank() || port < 1 || port > 65535
                    || username == null || username.isBlank()) throw new IllegalArgumentException("FTP 连接配置不合法");
        } else {
            try {
                URI endpoint = URI.create(webHdfsUrl);
                validateHttpEndpoint(endpoint);
                if (!(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")
                        || endpoint.getPath().equals("/webhdfs/v1"))) throw new IllegalArgumentException();
                for (String allowed : allowedDataNodeAuthorities) {
                    URI node = URI.create(endpoint.getScheme() + "://" + allowed);
                    validateHttpEndpoint(node);
                    if (!node.getPath().isEmpty()) throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new IllegalArgumentException("WebHDFS 连接配置不合法");
            }
        }
    }

    @Override
    public String toString() {
        return "RemoteFileConfig[protocol=" + protocol + "]";
    }

    private static boolean hasControl(String text) { return text.chars().anyMatch(c -> c < 32 || c == 127); }

    private static void validateHost(String host) {
        try {
            URI uri = URI.create("ftp://" + host);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || !uri.getPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new IllegalArgumentException("文件主机名不合法"); }
    }

    static void validateHttpEndpoint(URI uri) {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                || uri.getRawQuery() != null || uri.getPort() > 65535 || uri.getPort() == 0) {
            throw new IllegalArgumentException("WebHDFS 地址不合法");
        }
    }
}
