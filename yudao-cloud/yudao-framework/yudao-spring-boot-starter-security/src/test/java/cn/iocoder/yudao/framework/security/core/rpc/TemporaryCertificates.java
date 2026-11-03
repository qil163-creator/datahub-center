package cn.iocoder.yudao.framework.security.core.rpc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 测试专用：用当前 JDK 自带的 keytool 生成临时自签名证书，不把证书或私钥写入仓库。
 */
final class TemporaryCertificates {

    static final String STORE_PASSWORD = "sharing-test";

    private TemporaryCertificates() {
    }

    /** 生成一个证书；{@code startDate} 为 keytool 的 -startdate 表达式（如 "-10d"），null 表示当前时间。 */
    static X509Certificate generate(Path directory, String alias, String commonName, String startDate, int validityDays)
            throws Exception {
        Path store = keyStore(directory, alias, commonName, startDate, validityDays, List.of());
        return load(store, alias);
    }

    /** 生成证书并返回 PKCS12 密钥库路径，供需要私钥的 TLS 场景使用。 */
    static Path keyStore(Path directory, String alias, String commonName, String startDate, int validityDays,
                         List<String> subjectAlternativeNames) throws Exception {
        Path store = directory.resolve(alias + ".p12");
        Files.deleteIfExists(store);
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        var command = new java.util.ArrayList<>(List.of(keytool.toString(), "-genkeypair",
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

    static X509Certificate load(Path store, String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keyStore.load(input, STORE_PASSWORD.toCharArray());
        }
        return (X509Certificate) keyStore.getCertificate(alias);
    }

    static String pin(X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
}
