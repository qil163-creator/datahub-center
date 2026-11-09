package cn.iocoder.yudao.module.supply.service.dataapi.cipher;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.engines.SM4Engine;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 国密 SM4-GCM；128 位密钥由 SM3(用途上下文 || 主密钥) 取前 16 字节派生。
 */
public final class Sm4GcmSecretCipher extends GcmSecretCipherAlgorithm {

    public static final String ID = "SM4";

    private static final byte[] KEY_CONTEXT = "datahub:supply:app-secret:sm4-gcm".getBytes(StandardCharsets.UTF_8);

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected BlockCipher newEngine() {
        return new SM4Engine();
    }

    @Override
    protected byte[] deriveKey(String masterPassword) {
        byte[] master = masterPassword.getBytes(StandardCharsets.UTF_8);
        SM3Digest digest = new SM3Digest();
        digest.update(KEY_CONTEXT, 0, KEY_CONTEXT.length);
        digest.update(master, 0, master.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);
        return Arrays.copyOf(hash, 16);
    }

}
