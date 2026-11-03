package cn.iocoder.yudao.framework.etl.core.script;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** 双向 TLS 的私钥、证书和 CA 在实际执行端分别按受管引用读取，不进入序列化的作业配置。 */
public final class EtlScriptTls {
    private EtlScriptTls() { }

    public static SSLContext load(String privateKeyRef, String certificateRef, String authorityRef) {
        try {
            String pem = EtlManagedSecrets.readFromEnvironment(privateKeyRef);
            String certificate = EtlManagedSecrets.readFromEnvironment(certificateRef);
            String authorities = EtlManagedSecrets.readFromEnvironment(authorityRef);
            if (!pem.startsWith("-----BEGIN PRIVATE KEY-----") || !pem.strip().endsWith("-----END PRIVATE KEY-----")) throw new IllegalArgumentException();
            byte[] encoded = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
            PrivateKey key;
            try { key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded)); }
            catch (GeneralSecurityException notRsa) { key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded)); }
            Arrays.fill(encoded, (byte) 0);
            var factory = CertificateFactory.getInstance("X.509");
            var chain = factory.generateCertificates(new ByteArrayInputStream(certificate.getBytes(StandardCharsets.US_ASCII))).toArray(java.security.cert.Certificate[]::new);
            if (chain.length < 1 || chain.length > 4) throw new IllegalArgumentException();
            for (var item : chain) ((X509Certificate) item).checkValidity();
            var keys = KeyStore.getInstance("PKCS12"); keys.load(null, null); keys.setKeyEntry("client", key, new char[0], chain);
            var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null);
            int count = 0;
            for (var item : factory.generateCertificates(new ByteArrayInputStream(authorities.getBytes(StandardCharsets.US_ASCII)))) {
                var authority = (X509Certificate) item; authority.checkValidity();
                if (authority.getBasicConstraints() < 0 || ++count > 4) throw new IllegalArgumentException();
                trust.setCertificateEntry("authority-" + count, authority);
            }
            if (count == 0) throw new IllegalArgumentException();
            var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keyManagers.init(keys, new char[0]);
            var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trustManagers.init(trust);
            var context = SSLContext.getInstance("TLS"); context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception failure) { throw new IllegalStateException("脚本执行身份的受管证书或私钥不可用"); }
    }

    public static String fingerprint(X509Certificate certificate) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())); }
        catch (GeneralSecurityException failure) { throw new IllegalArgumentException("脚本执行证书无效"); }
    }
}
