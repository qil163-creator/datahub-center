package cn.iocoder.yudao.framework.security.core.rpc;

import jakarta.servlet.http.HttpServletRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从 Servlet 容器直接完成的 TLS 握手中解析已认证的对端服务身份。
 *
 * 只信任容器写入的 {@value #CERTIFICATE_ATTRIBUTE} 请求属性（客户端证书链）以及 {@link HttpServletRequest#isSecure()}；
 * 不读取 X-Forwarded-Client-Cert、login-user、tenant-id 等任何可由调用方伪造的 HTTP 头。
 * 身份由叶证书 DER 编码的 SHA-256 指纹（小写十六进制）与调用方给定的“服务标识 → 指纹集合”对照得出，
 * 同一指纹只能属于一个服务标识；证书必须在有效期内。本类不含任何业务模块类型。
 */
public final class VerifiedServicePeerResolver {

    /** Servlet 规范定义的客户端证书链属性，仅由容器在 TLS 握手后写入。 */
    public static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private static final int SHA256_HEX_LENGTH = 64;

    /**
     * 校验并规范化“服务标识 → 证书 SHA-256 指纹集合”配置。
     *
     * @return 规范化后的副本：标识去空白、指纹小写；空标识、空集合、非十六进制指纹或同一指纹映射到多个标识时拒绝
     */
    public static Map<String, Set<String>> requireUnambiguous(Map<String, Set<String>> allowedCertificateSha256) {
        if (allowedCertificateSha256 == null) {
            throw new IllegalArgumentException("允许的服务证书指纹配置不能为空");
        }
        Map<String, Set<String>> normalized = new HashMap<>();
        Map<String, String> owners = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : allowedCertificateSha256.entrySet()) {
            String service = entry.getKey() == null ? "" : entry.getKey().strip();
            if (service.isEmpty()) {
                throw new IllegalArgumentException("服务标识不能为空");
            }
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalArgumentException("服务 " + service + " 至少需要一个证书指纹");
            }
            Set<String> pins = new LinkedHashSet<>();
            for (String pin : entry.getValue()) {
                String canonical = canonicalPin(pin);
                if (canonical == null) {
                    throw new IllegalArgumentException("服务 " + service + " 的证书指纹必须是 SHA-256 十六进制");
                }
                String owner = owners.putIfAbsent(canonical, service);
                if (owner != null && !owner.equals(service)) {
                    throw new IllegalArgumentException("同一证书指纹不能同时属于服务 " + owner + " 与 " + service);
                }
                pins.add(canonical);
            }
            if (normalized.putIfAbsent(service, Set.copyOf(pins)) != null) {
                throw new IllegalArgumentException("服务标识重复：" + service);
            }
        }
        return Map.copyOf(normalized);
    }

    /**
     * 解析请求对端的已认证服务身份。
     *
     * @param request                  当前请求
     * @param allowedCertificateSha256 服务标识 → 允许的叶证书 SHA-256 指纹；轮换期间同一服务可配置多个指纹
     * @return 唯一匹配的服务标识；非 TLS 通道、无证书、证书不在有效期、指纹未配置或配置有歧义时返回空
     */
    public Optional<String> resolve(HttpServletRequest request, Map<String, Set<String>> allowedCertificateSha256) {
        if (request == null || !request.isSecure() || allowedCertificateSha256 == null
                || allowedCertificateSha256.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Set<String>> peers;
        try {
            peers = requireUnambiguous(allowedCertificateSha256);
        } catch (IllegalArgumentException invalidConfiguration) {
            return Optional.empty();
        }
        X509Certificate leaf = leafCertificate(request);
        if (leaf == null) {
            return Optional.empty();
        }
        try {
            leaf.checkValidity();
        } catch (CertificateException notValidNow) {
            return Optional.empty();
        }
        String pin = fingerprint(leaf);
        if (pin == null) {
            return Optional.empty();
        }
        String matched = null;
        for (Map.Entry<String, Set<String>> entry : peers.entrySet()) {
            if (entry.getValue().contains(pin)) {
                if (matched != null) {
                    return Optional.empty();
                }
                matched = entry.getKey();
            }
        }
        return Optional.ofNullable(matched);
    }

    /** 计算证书 DER 编码的 SHA-256 小写十六进制指纹；编码失败返回 null。 */
    public static String fingerprint(X509Certificate certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (CertificateEncodingException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private static X509Certificate leafCertificate(HttpServletRequest request) {
        Object attribute = request.getAttribute(CERTIFICATE_ATTRIBUTE);
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0 || chain[0] == null) {
            return null;
        }
        // 容器按“叶证书在前”写入链；只有叶证书代表对端身份，链中其他证书不参与 pin 比对。
        return chain[0];
    }

    private static String canonicalPin(String pin) {
        if (pin == null) {
            return null;
        }
        String canonical = pin.strip().toLowerCase(Locale.ROOT);
        if (canonical.length() != SHA256_HEX_LENGTH) {
            return null;
        }
        for (int i = 0; i < canonical.length(); i++) {
            char c = canonical.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return null;
            }
        }
        return canonical;
    }
}
