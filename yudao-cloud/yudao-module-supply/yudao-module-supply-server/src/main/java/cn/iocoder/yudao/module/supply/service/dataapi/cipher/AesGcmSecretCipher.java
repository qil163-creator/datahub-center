package cn.iocoder.yudao.module.supply.service.dataapi.cipher;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;

import java.nio.charset.StandardCharsets;

/**
 * AES-256-GCM；256 位密钥由 SHA-256(用途上下文 || 主密钥) 派生。
 */
public final class AesGcmSecretCipher extends GcmSecretCipherAlgorithm {

    public static final String ID = "AES";

    private static final byte[] KEY_CONTEXT = "datahub:supply:app-secret:aes-256-gcm".getBytes(StandardCharsets.UTF_8);

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected BlockCipher newEngine() {
        return AESEngine.newInstance();
    }

    @Override
    protected byte[] deriveKey(String masterPassword) {
        byte[] master = masterPassword.getBytes(StandardCharsets.UTF_8);
        SHA256Digest digest = new SHA256Digest();
        digest.update(KEY_CONTEXT, 0, KEY_CONTEXT.length);
        digest.update(master, 0, master.length);
        byte[] key = new byte[digest.getDigestSize()];
        digest.doFinal(key, 0);
        return key;
    }

}
