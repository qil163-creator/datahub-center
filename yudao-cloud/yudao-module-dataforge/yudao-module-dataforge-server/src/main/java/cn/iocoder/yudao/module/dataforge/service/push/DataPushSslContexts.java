package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * 从服务端证书文件构建 SSLContext，供 HTTP mTLS / Kafka / Rabbit 使用。
 * 路径必须是本机可读的绝对文件，不接受目录或相对穿越。
 *
 * @author Li
 */
public final class DataPushSslContexts {

    /**
     * 限制数据推送SSL上下文工具的实例化，统一通过静态能力使用。
     */
    private DataPushSslContexts() {
    }

    /**
     * 创建数据推送SSL上下文。
     *
     * @param truststorePath 信任库路径
     * @param truststorePassword 信任库密码
     * @param keystorePath 密钥库路径
     * @param keystorePassword 密钥库密码
     * @return SSL上下文
     */
    public static SSLContext create(String truststorePath, String truststorePassword,
            String keystorePath, String keystorePassword) {
        if (StrUtil.isAllBlank(truststorePath, keystorePath)) {
            return null;
        }
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            if (StrUtil.isNotBlank(truststorePath)) {
                tmf.init(loadKeyStore(truststorePath, truststorePassword));
            } else {
                tmf.init((KeyStore) null);
            }
            KeyManagerFactory kmf = null;
            if (StrUtil.isNotBlank(keystorePath)) {
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(loadKeyStore(keystorePath, keystorePassword),
                        StrUtil.nullToEmpty(keystorePassword).toCharArray());
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return context;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("加载 TLS 证书失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 校验可选文件。
     *
     * @param path 路径
     */
    public static void validateOptionalFile(String path) {
        if (StrUtil.isNotBlank(path)) {
            requireReadableFile(path);
        }
    }

    /**
     * 校验可读取文件。
     *
     * @param path 路径
     * @return 路径
     */
    public static Path requireReadableFile(String path) {
        if (StrUtil.isBlank(path)) {
            return null;
        }
        Path resolved = Path.of(path.trim()).toAbsolutePath().normalize();
        if (!resolved.isAbsolute() || !Files.isRegularFile(resolved) || !Files.isReadable(resolved)) {
            throw new IllegalArgumentException("证书文件不存在或不可读：" + path);
        }
        return resolved;
    }

    /**
     * 加载键存储。
     *
     * @param path 路径
     * @param password 密码
     * @return 键存储
     */
    private static KeyStore loadKeyStore(String path, String password) throws Exception {
        Path file = requireReadableFile(path);
        String type = file.getFileName().toString().toLowerCase().endsWith(".p12")
                || file.getFileName().toString().toLowerCase().endsWith(".pfx")
                ? "PKCS12" : "JKS";
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, StrUtil.nullToEmpty(password).toCharArray());
        }
        return store;
    }

}
