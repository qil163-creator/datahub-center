package cn.iocoder.yudao.module.supply.framework.security;

import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 测试专用：用当前 JDK 自带的 keytool 生成临时自签名证书与密钥库，不把证书或私钥写入仓库。
 */
public final class TemporaryCertificates {

    public static final String STORE_PASSWORD = "sharing-test";

    private TemporaryCertificates() {
    }

    /** 生成证书并返回 PKCS12 密钥库路径；{@code startDate} 为 keytool 的 -startdate 表达式，null 表示当前时间。 */
    public static Path keyStore(Path directory, String alias, String commonName, String startDate, int validityDays,
                                List<String> subjectAlternativeNames) throws Exception {
        Path store = directory.resolve(alias + ".p12");
        Files.deleteIfExists(store);
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        List<String> command = new ArrayList<>(List.of(keytool.toString(), "-genkeypair",
                "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=" + commonName, "-validity", String.valueOf(validityDays),
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", STORE_PASSWORD));
        if (startDate != null) {
            command.add("-startdate");
            command.add(startDate);
        }
        if (!subjectAlternativeNames.isEmpty()) {
            command.add("-ext");
            command.add("SAN=" + String.join(",", subjectAlternativeNames));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool failed: " + new String(output));
        }
        return store;
    }

    public static X509Certificate generate(Path directory, String alias, String commonName) throws Exception {
        return certificate(keyStore(directory, alias, commonName, null, 30, List.of()), alias);
    }

    public static KeyStore load(Path store) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keyStore.load(input, STORE_PASSWORD.toCharArray());
        }
        return keyStore;
    }

    public static X509Certificate certificate(Path store, String alias) throws Exception {
        return (X509Certificate) load(store).getCertificate(alias);
    }

    public static String pin(X509Certificate certificate) {
        return VerifiedServicePeerResolver.fingerprint(certificate);
    }

    /** 内存信任库：只信任给定证书，不使用系统默认信任锚。 */
    public static KeyStore trustStore(X509Certificate... trusted) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        for (int i = 0; i < trusted.length; i++) {
            trust.setCertificateEntry("trusted-" + i, trusted[i]);
        }
        return trust;
    }

    /** 由“自己的密钥库（可为 null，表示不出示证书）+ 信任库”构成 TLS bundle。 */
    public static SslBundle bundle(Path ownKeyStore, KeyStore trustStore) throws Exception {
        KeyStore identity = ownKeyStore == null ? null : load(ownKeyStore);
        return SslBundle.of(SslStoreBundle.of(identity, STORE_PASSWORD, trustStore));
    }

    public static PrivateKey privateKey(Path store, String alias) throws Exception {
        return (PrivateKey) load(store).getKey(alias, STORE_PASSWORD.toCharArray());
    }

    public static Certificate[] chain(Path store, String alias) throws Exception {
        return load(store).getCertificateChain(alias);
    }
}
