package cn.iocoder.yudao.module.governance.service.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyKeyRefDO;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyKeyRefMapper;
import cn.iocoder.yudao.module.governance.framework.security.SafetyKeyCenterProperties;
import jakarta.annotation.Resource;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.KEY_CENTER_NOT_CONFIGURED;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.KEY_CIPHERTEXT_INVALID;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.KEY_REF_NOT_EXISTS;

/**
 * 字段加密密钥中心（KEY_CENTER）：数据密钥由部署环境注入的主密钥按密钥引用派生，平台库不保存任何密钥材料。
 * <p>
 * 派生：HKDF-SHA256(主密钥, salt = 引用的 externalKeyId, info = "datahub:key-center:" + 编码) → 16 字节 SM4 密钥。
 * 加密：SM4/GCM/NoPadding，随机 12 字节 IV，AAD 绑定引用编码与主密钥版本，防止跨引用、跨版本挪用密文。
 * 密文：{@code KC1:<编码>:<主密钥版本>:<Base64URL(iv || 密文 || tag)>}，按携带的版本解密，支持轮换。
 * 主密钥缺失、引用停用或密文被篡改时一律抛错，调用方失败关闭。
 */
@Service
public class SafetyKeyCenterService {

    /** 密文前缀。 */
    public static final String PREFIX = "KC1";
    /** 引用编码格式。 */
    public static final Pattern KEY_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_MASTER_BYTES = 32;
    /** 引用行缓存时间：停用引用最迟在这个时间内失效。 */
    private static final long REF_CACHE_MILLIS = 30_000;
    private static final Provider BC = new BouncyCastleProvider();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, CachedRef> refs = new ConcurrentHashMap<>();

    @Resource
    private SafetyKeyCenterProperties properties;
    @Resource
    private SafetyKeyRefMapper keyRefMapper;

    /**
     * 主密钥是否已配置可用（当前版本存在且长度足够）。
     */
    public boolean available() {
        try {
            masterKey(properties.getActiveVersion());
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * 当前主密钥版本；未配置时为空。
     */
    public String activeVersion() {
        return available() ? properties.getActiveVersion() : null;
    }

    /**
     * 用引用的数据密钥加密文本。
     */
    public String encrypt(String keyCode, String plaintext) {
        SafetyKeyRefDO ref = enabledRef(keyCode);
        String version = properties.getActiveVersion();
        byte[] key = dataKey(ref, version);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", BC);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "SM4"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(ref.getKeyCode(), version));
            byte[] encrypted = cipher.doFinal(StrUtil.nullToEmpty(plaintext).getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return PREFIX + ":" + ref.getKeyCode() + ":" + version + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("字段加密失败", failure);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * 解密本中心产生的密文。只供服务端内部使用（如授权的数据交付），不经管理端接口暴露。
     */
    public String decrypt(String ciphertext) {
        String[] parts = StrUtil.nullToEmpty(ciphertext).split(":", 4);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            throw exception(KEY_CIPHERTEXT_INVALID);
        }
        SafetyKeyRefDO ref = enabledRef(parts[1]);
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[3]);
        } catch (IllegalArgumentException invalid) {
            throw exception(KEY_CIPHERTEXT_INVALID);
        }
        if (payload.length <= IV_BYTES) {
            throw exception(KEY_CIPHERTEXT_INVALID);
        }
        byte[] key = dataKey(ref, parts[2]);
        try {
            Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", BC);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "SM4"),
                    new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            cipher.updateAAD(aad(ref.getKeyCode(), parts[2]));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException tampered) {
            throw exception(KEY_CIPHERTEXT_INVALID);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * 引用变更后立即失效缓存。
     */
    public void evict(String keyCode) {
        if (keyCode != null) {
            refs.remove(keyCode);
        }
    }

    // ==================== 内部 ====================

    /**
     * 读取启用的 KEY_CENTER 引用（带短时缓存）。
     */
    private SafetyKeyRefDO enabledRef(String keyCode) {
        if (keyCode == null || !KEY_CODE.matcher(keyCode).matches()) {
            throw exception(KEY_REF_NOT_EXISTS, String.valueOf(keyCode));
        }
        long now = System.currentTimeMillis();
        CachedRef cached = refs.get(keyCode);
        SafetyKeyRefDO ref;
        if (cached != null && now - cached.loadedAt() < REF_CACHE_MILLIS) {
            ref = cached.ref();
        } else {
            ref = keyRefMapper.selectByCode(keyCode);
            refs.put(keyCode, new CachedRef(ref, now));
        }
        if (ref == null || !Integer.valueOf(SafetyKeyRefDO.STATUS_ENABLED).equals(ref.getStatus())
                || !SafetyKeyRefDO.PROVIDER_KEY_CENTER.equals(ref.getProvider())
                || !SafetyKeyRefDO.KEY_TYPE_SM4.equals(ref.getKeyType()) || StrUtil.isBlank(ref.getExternalKeyId())) {
            throw exception(KEY_REF_NOT_EXISTS, keyCode);
        }
        return ref;
    }

    /**
     * 派生数据密钥。
     */
    private byte[] dataKey(SafetyKeyRefDO ref, String version) {
        byte[] master = masterKey(version);
        try {
            return hkdf(master, ref.getExternalKeyId().getBytes(StandardCharsets.UTF_8),
                    ("datahub:key-center:" + ref.getKeyCode()).getBytes(StandardCharsets.UTF_8), 16);
        } finally {
            Arrays.fill(master, (byte) 0);
        }
    }

    /**
     * 读取主密钥；缺失或过短时不可用。
     */
    private byte[] masterKey(String version) {
        Map<String, String> keys = properties.getMasterKeys();
        String encoded = version == null || keys == null ? null : keys.get(version);
        if (StrUtil.isBlank(encoded)) {
            throw exception(KEY_CENTER_NOT_CONFIGURED);
        }
        byte[] master;
        try {
            master = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException invalid) {
            throw exception(KEY_CENTER_NOT_CONFIGURED);
        }
        if (master.length < MIN_MASTER_BYTES) {
            Arrays.fill(master, (byte) 0);
            throw exception(KEY_CENTER_NOT_CONFIGURED);
        }
        return master;
    }

    /**
     * RFC 5869 HKDF-SHA256。
     */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] result = new byte[length];
            byte[] block = new byte[0];
            int offset = 0;
            for (int counter = 1; offset < length; counter++) {
                mac.update(block);
                mac.update(info);
                mac.update((byte) counter);
                block = mac.doFinal();
                int copy = Math.min(block.length, length - offset);
                System.arraycopy(block, 0, result, offset, copy);
                offset += copy;
            }
            Arrays.fill(prk, (byte) 0);
            return result;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("密钥派生失败", failure);
        }
    }

    /**
     * 附加认证数据。
     */
    private static byte[] aad(String keyCode, String version) {
        return (PREFIX + ":" + keyCode + ":" + version).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 引用缓存项。
     */
    private record CachedRef(SafetyKeyRefDO ref, long loadedAt) {
    }
}
