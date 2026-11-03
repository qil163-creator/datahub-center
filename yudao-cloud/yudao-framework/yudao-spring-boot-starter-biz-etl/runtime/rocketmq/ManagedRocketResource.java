package cn.iocoder.yudao.framework.etl.flink.rocketmq;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.ClientConfig;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.netty.TlsSystemConfig;

import java.io.Serializable;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 工作端才解析引用，秘密文件必须绑定批准的 NameServer、主题、消息组和 TLS。 */
public final class ManagedRocketResource implements Serializable {
    private static final long serialVersionUID = 1L;
    final String servers, topic, group, reference;
    final boolean tls, output;
    public ManagedRocketResource(String servers, String topic, String group, String reference, boolean tls, boolean output) {
        this.servers = servers; this.topic = topic; this.group = group; this.reference = reference; this.tls = tls; this.output = output;
    }
    String identity() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((servers + "\n" + topic + "\n" + group + "\n" + tls).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("RocketMQ 资源标识不可用"); }
    }
    public RPCHook credentials() {
        try {
            if (tls) requirePinnedTls();
            Properties values = new Properties(); values.load(new StringReader(EtlManagedSecrets.readFromEnvironment(reference)));
            if (!values.stringPropertyNames().equals(Set.of("nameservers", "sourceTopics", "sinkTopics", "consumerGroups", "producerGroups", "useTls", "accessKey", "secretKey"))) throw new IllegalArgumentException();
            if (!servers.equals(values.getProperty("nameservers")) || !Boolean.toString(tls).equals(values.getProperty("useTls"))
                    || !allowed(values.getProperty(output ? "sinkTopics" : "sourceTopics"), topic)
                    || !allowed(values.getProperty(output ? "producerGroups" : "consumerGroups"), group)) throw new IllegalArgumentException();
            String access = values.getProperty("accessKey"), secret = values.getProperty("secretKey");
            if (access == null || !access.matches("[A-Za-z0-9_.-]{1,128}") || secret == null || secret.isEmpty() || secret.length() > 1024
                    || secret.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            return new AclClientRPCHook(new SessionCredentials(access, secret));
        } catch (Exception failure) { throw new IllegalStateException("RocketMQ 凭据、批准资源或 TLS 验证不可用"); }
    }
    public void configure(ClientConfig client) {
        client.setNamesrvAddr(servers); client.setUseTLS(tls); client.setInstanceName("dh_" + UUID.randomUUID().toString().replace("-", ""));
        client.setClientCallbackExecutorThreads(1); client.setPersistConsumerOffsetInterval(1000);
    }
    private static void requirePinnedTls() throws Exception {
        // SDK 不做主机名核对，因此仅接受管理员钉住的终端证书；不接受通用 CA 信任范围。
        if (TlsSystemConfig.tlsTestModeEnable || !TlsSystemConfig.tlsClientAuthServer || TlsSystemConfig.tlsClientTrustCertPath == null
                || Files.exists(Path.of(TlsSystemConfig.tlsConfigFile), LinkOption.NOFOLLOW_LINKS)
                || TlsSystemConfig.tlsClientKeyPassword != null && !TlsSystemConfig.tlsClientKeyPassword.isBlank())
            throw new IllegalStateException("RocketMQ TLS 需使用工作进程属性和明确的终端证书钉住配置");
        Path file = Path.of(TlsSystemConfig.tlsClientTrustCertPath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 262144) throw new IllegalStateException();
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var certificates = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(input);
            if (certificates.isEmpty() || certificates.size() > 16) throw new IllegalStateException();
            for (var certificate : certificates) {
                if (!(certificate instanceof java.security.cert.X509Certificate x509) || x509.getBasicConstraints() >= 0) throw new IllegalStateException();
                x509.checkValidity();
            }
        }
    }
    private static boolean allowed(String entries, String expected) { return entries != null && Arrays.asList(entries.split(",", -1)).contains(expected); }
}
