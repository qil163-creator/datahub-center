package cn.iocoder.yudao.module.supply.service.dataapi.cipher;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 基于 128 位分组密码的 GCM 认证加密公共实现。
 *
 * <p>载荷为 {@code base64(12 字节随机 IV || 密文 || 16 字节认证标签)}；附加数据绑定“供应应用密钥”用途，
 * 防止同一主密钥下其他字段的密文被替换进来。</p>
 */
public abstract class GcmSecretCipherAlgorithm implements SecretCipherAlgorithm {

    private static final byte[] AAD = "dh_supply_app.secret".getBytes(StandardCharsets.UTF_8);

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 创建新的分组密码引擎实例（引擎非线程安全，每次加解密独立创建）。
     *
     * @return 分组密码引擎
     */
    protected abstract BlockCipher newEngine();

    /**
     * 由平台主密钥派生本算法使用的密钥，必须做用途隔离，不能直接使用主密钥字节。
     *
     * @param masterPassword 平台主密钥
     * @return 算法密钥
     */
    protected abstract byte[] deriveKey(String masterPassword);

    @Override
    public String encrypt(String plain, String masterPassword) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] input = plain.getBytes(StandardCharsets.UTF_8);
        GCMModeCipher cipher = GCMBlockCipher.newInstance(newEngine());
        cipher.init(true, new AEADParameters(new KeyParameter(deriveKey(masterPassword)), TAG_BITS, iv, AAD));
        byte[] out = new byte[cipher.getOutputSize(input.length)];
        int len = cipher.processBytes(input, 0, input.length, out, 0);
        try {
            len += cipher.doFinal(out, len);
        } catch (InvalidCipherTextException ex) {
            throw new IllegalStateException(id() + " encrypt failed", ex);
        }
        ByteBuffer packed = ByteBuffer.allocate(IV_BYTES + len).put(iv).put(out, 0, len);
        return Base64.getEncoder().encodeToString(packed.array());
    }

    @Override
    public String decrypt(String payload, String masterPassword) {
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(id() + " secret is malformed", ex);
        }
        if (packed.length <= IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException(id() + " secret is malformed");
        }
        byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
        GCMModeCipher cipher = GCMBlockCipher.newInstance(newEngine());
        cipher.init(false, new AEADParameters(new KeyParameter(deriveKey(masterPassword)), TAG_BITS, iv, AAD));
        byte[] out = new byte[cipher.getOutputSize(packed.length - IV_BYTES)];
        int len = cipher.processBytes(packed, IV_BYTES, packed.length - IV_BYTES, out, 0);
        try {
            len += cipher.doFinal(out, len);
        } catch (InvalidCipherTextException ex) {
            // 认证标签不符：主密钥不一致或密文被篡改。
            throw new IllegalStateException(id() + " secret authentication failed", ex);
        }
        return new String(out, 0, len, StandardCharsets.UTF_8);
    }

}
