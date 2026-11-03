package cn.iocoder.yudao.framework.dbswitch.data.recovery;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 共享的有界部署密钥环；调用方提供按领域隔离的 AAD 和状态信封。 */
final class RecoveryCrypto {

    record Sealed(String keyId, byte[] nonce, byte[] payload) {

    }

    private final String currentKeyId;

    private final Map<String, SecretKey> keys;

    private final SecureRandom random = new SecureRandom();

    /**
     * 校验构造参数并初始化 {@code RecoveryCrypto}。
     */
    RecoveryCrypto(String currentKeyId, Map<String, ? extends SecretKey> keyring) {
        var snapshot = new LinkedHashMap<String, SecretKey>();
        Objects.requireNonNull(keyring).forEach((id, key) -> {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}") || key == null || !"AES".equalsIgnoreCase(
                    key.getAlgorithm())) {
                throw invalid();
            }
            byte[] value = key.getEncoded();
            if (value == null || value.length != 32) {
                throw invalid();
            }
            snapshot.put(id, new SecretKeySpec(value.clone(), "AES"));
        });
        if (snapshot.isEmpty() || snapshot.size() > 16 || !snapshot.containsKey(currentKeyId)) {
            throw invalid();
        }
        this.currentKeyId = currentKeyId;
        this.keys = Map.copyOf(snapshot);
    }

    /**
     * 返回当前键编号。
     */
    String currentKeyId() {
        return currentKeyId;
    }

    /**
     * 对当前恢复材料进行封装或加密并返回可保存的载荷。
     */
    Sealed seal(byte[] plaintext, java.util.function.Function<String, byte[]> aad) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        return new Sealed(currentKeyId, nonce,
                crypt(Cipher.ENCRYPT_MODE, currentKeyId, nonce, aad.apply(currentKeyId), plaintext));
    }

    /**
     * 使用当前密码协议加密或解密载荷。
     */
    byte[] crypt(int mode, String keyId, byte[] nonce, byte[] aad, byte[] payload) {
        SecretKey key = keys.get(keyId);
        if (key == null || nonce == null || nonce.length != 12) {
            throw invalid();
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(payload);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Recovery material authentication failed");
        }
    }

    /**
     * 使用指定密钥计算持久化状态的消息认证码。
     */
    byte[] stateMac(String keyId, String derivationDomain, byte[] envelope) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw invalid();
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            byte[] derived = mac.doFinal(derivationDomain.getBytes(StandardCharsets.UTF_8));
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            return mac.doFinal(envelope);
        } catch (GeneralSecurityException failure) {
            throw invalid();
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid recovery keyring or authentication input");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "RecoveryCrypto[keys=" + keys.size() + "]";
    }
}
