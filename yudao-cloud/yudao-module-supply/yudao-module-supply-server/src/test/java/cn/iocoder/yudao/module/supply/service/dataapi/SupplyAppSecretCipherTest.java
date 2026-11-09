package cn.iocoder.yudao.module.supply.service.dataapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplyAppSecretCipherTest {

    private static final String MASTER = "unit-test-master-key";

    @ParameterizedTest
    @ValueSource(strings = {"SM4", "AES", "sm4", " aes "})
    void eachRegisteredAlgorithmRoundTripsWithRandomIv(String algorithm) {
        String first = SupplyAppSecretCipher.encrypt("app-secret-明文", algorithm, MASTER);
        String second = SupplyAppSecretCipher.encrypt("app-secret-明文", algorithm, MASTER);

        assertThat(first).startsWith(algorithm.trim().toUpperCase() + ":").isNotEqualTo(second);
        assertThat(SupplyAppSecretCipher.decrypt(first, MASTER)).isEqualTo("app-secret-明文");
        assertThat(SupplyAppSecretCipher.decrypt(second, MASTER)).isEqualTo("app-secret-明文");
    }

    @Test
    void defaultAlgorithmIsSm4AndBothFormatsCoexist() {
        String sm4 = SupplyAppSecretCipher.encrypt("secret", null, MASTER);
        String aes = SupplyAppSecretCipher.encrypt("secret", "AES", MASTER);

        assertThat(sm4).startsWith("SM4:");
        assertThat(aes).startsWith("AES:");
        // 切换写入算法后，另一种算法写入的密文仍按前缀解密。
        assertThat(SupplyAppSecretCipher.decrypt(sm4, MASTER)).isEqualTo("secret");
        assertThat(SupplyAppSecretCipher.decrypt(aes, MASTER)).isEqualTo("secret");
        assertThat(SupplyAppSecretCipher.supportedAlgorithms()).containsExactlyInAnyOrder("SM4", "AES");
    }

    @Test
    void unknownAlgorithmIsRejected() {
        assertThatThrownBy(() -> SupplyAppSecretCipher.encrypt("secret", "DES", MASTER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DES");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain-secret", "ENC:abc", "XYZ:abc", ":abc", "SM4:not-base64!", "SM4:AAAA"})
    void unsupportedOrMalformedStoredValuesFailClosed(String stored) {
        assertThatThrownBy(() -> SupplyAppSecretCipher.decrypt(stored, MASTER))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SM4", "AES"})
    void tamperedCiphertextOrWrongMasterKeyFailsClosed(String algorithm) {
        String stored = SupplyAppSecretCipher.encrypt("secret", algorithm, MASTER);
        String prefix = algorithm + ":";
        byte[] packed = Base64.getDecoder().decode(stored.substring(prefix.length()));
        packed[packed.length - 1] ^= 0x01;
        String tampered = prefix + Base64.getEncoder().encodeToString(packed);

        assertThatThrownBy(() -> SupplyAppSecretCipher.decrypt(tampered, MASTER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SupplyAppSecretCipher.decrypt(stored, "another-master-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ciphertextFitsSecretColumn() {
        // 应用密钥 40 位；dh_supply_app.secret 为 varchar(256)。
        String secret = "0123456789abcdef0123456789abcdef01234567";
        assertThat(SupplyAppSecretCipher.encrypt(secret, "SM4", MASTER)).hasSizeLessThanOrEqualTo(256);
        assertThat(SupplyAppSecretCipher.encrypt(secret, "AES", MASTER)).hasSizeLessThanOrEqualTo(256);
    }
}
