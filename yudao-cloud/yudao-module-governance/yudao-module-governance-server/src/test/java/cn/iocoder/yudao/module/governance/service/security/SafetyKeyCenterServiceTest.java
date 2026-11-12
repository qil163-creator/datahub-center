package cn.iocoder.yudao.module.governance.service.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyKeyRefDO;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyKeyRefMapper;
import cn.iocoder.yudao.module.governance.framework.security.SafetyKeyCenterProperties;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskEngine;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleConfigValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 字段加密密钥中心：派生、加解密往返、防篡改、跨引用挪用、轮换、失败关闭，以及规则校验不再接受内联密钥。
 */
class SafetyKeyCenterServiceTest {

    private static final String V1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String V2 = Base64.getEncoder().encodeToString(filled(32, (byte) 7));

    private SafetyKeyCenterProperties properties;
    private SafetyKeyRefMapper mapper;
    private SafetyKeyCenterService center;

    @BeforeEach
    void setUp() {
        properties = new SafetyKeyCenterProperties();
        properties.setMasterKeys(new LinkedHashMap<>(Map.of("1", V1, "2", V2)));
        properties.setActiveVersion("1");
        mapper = mock(SafetyKeyRefMapper.class);
        when(mapper.selectByCode("pii")).thenReturn(ref("pii", "kc:salt-a", SafetyKeyRefDO.STATUS_ENABLED));
        when(mapper.selectByCode("other")).thenReturn(ref("other", "kc:salt-b", SafetyKeyRefDO.STATUS_ENABLED));
        when(mapper.selectByCode("off")).thenReturn(ref("off", "kc:salt-c", SafetyKeyRefDO.STATUS_DISABLED));
        center = new SafetyKeyCenterService();
        ReflectionTestUtils.setField(center, "properties", properties);
        ReflectionTestUtils.setField(center, "keyRefMapper", mapper);
    }

    @Test
    void roundTripIsRandomizedAndVersioned() {
        String first = center.encrypt("pii", "13800000000");
        String second = center.encrypt("pii", "13800000000");
        assertThat(first).startsWith("KC1:pii:1:").doesNotContain("13800000000").isNotEqualTo(second);
        assertThat(center.decrypt(first)).isEqualTo("13800000000");
    }

    @Test
    void rotationKeepsOldCiphertextDecryptable() {
        String old = center.encrypt("pii", "x");
        properties.setActiveVersion("2");
        String rotated = center.encrypt("pii", "x");
        assertThat(rotated).startsWith("KC1:pii:2:");
        assertThat(center.decrypt(old)).isEqualTo("x");
        assertThat(center.decrypt(rotated)).isEqualTo("x");
    }

    @Test
    void tamperedOrMovedCiphertextIsRejected() {
        String cipher = center.encrypt("pii", "secret");
        String[] parts = cipher.split(":", 4);
        byte[] payload = Base64.getUrlDecoder().decode(parts[3]);
        payload[payload.length - 1] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":" + parts[2] + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        assertThatThrownBy(() -> center.decrypt(tampered)).isInstanceOf(ServiceException.class);
        // 换到另一个引用或另一个版本下解密都会因 AAD 不匹配失败。
        assertThatThrownBy(() -> center.decrypt(cipher.replace("KC1:pii:", "KC1:other:")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> center.decrypt(cipher.replace("KC1:pii:1:", "KC1:pii:2:")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> center.decrypt("garbage")).isInstanceOf(ServiceException.class);
    }

    @Test
    void missingMasterKeyOrDisabledReferenceFailsClosed() {
        assertThatThrownBy(() -> center.encrypt("off", "x")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> center.encrypt("missing", "x")).isInstanceOf(ServiceException.class);
        properties.setMasterKeys(Map.of("1", Base64.getEncoder().encodeToString(new byte[16])));
        assertThat(center.available()).isFalse();
        assertThatThrownBy(() -> center.encrypt("pii", "x")).isInstanceOf(ServiceException.class);
        properties.setMasterKeys(Map.of());
        assertThat(center.activeVersion()).isNull();
    }

    @Test
    void hkdfMatchesRfc5869TestCase1() {
        byte[] ikm = filled(22, (byte) 0x0b);
        byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
        byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
        assertThat(HexFormat.of().formatHex(SafetyKeyCenterService.hkdf(ikm, salt, info, 42))).isEqualTo(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
    }

    @Test
    void engineUsesTheKeyCenterAndNeverFallsBackToPlaintext() {
        Map<String, Object> config = Map.of("encryptType", "KEY_REF", "keyCode", "pii");
        Object masked = SafetyMaskEngine.mask("13800000000", "ENCRYPT", config, List.of(), center::encrypt);
        assertThat(center.decrypt((String) masked)).isEqualTo("13800000000");
        assertThatThrownBy(() -> SafetyMaskEngine.mask("13800000000", "ENCRYPT", config, List.of()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void validatorRejectsInlineSecretsAndAcceptsKeyReferences() {
        assertThatThrownBy(() -> SafetyRuleConfigValidator.validate("ENCRYPT",
                Map.of("encryptType", "AES", "secretKey", "k"), List.of())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SafetyRuleConfigValidator.validate("ENCRYPT",
                Map.of("encryptType", "SM", "algorithm", "SM4", "secretKey", "k"), List.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SafetyRuleConfigValidator.validate("ENCRYPT",
                Map.of("encryptType", "SM", "algorithm", "SM2", "publicKey", "p", "privateKey", "s"), List.of()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SafetyRuleConfigValidator.validate("ENCRYPT",
                Map.of("encryptType", "KEY_REF", "keyCode", "1bad"), List.of())).isInstanceOf(ServiceException.class);
        SafetyRuleConfigValidator.validate("ENCRYPT", Map.of("encryptType", "KEY_REF", "keyCode", "pii"), List.of());
        SafetyRuleConfigValidator.validate("ENCRYPT",
                Map.of("encryptType", "SM", "algorithm", "SM2", "publicKey", "p"), List.of());
    }

    @Test
    void inlineSecretDetectionOnlyLooksAtKeys() {
        assertThat(SafetyKeyRefService.hasInlineSecret("{\"encryptType\":\"AES\",\"secretKey\":\"k\"}")).isTrue();
        assertThat(SafetyKeyRefService.hasInlineSecret("{\"encryptType\":\"KEY_REF\",\"keyCode\":\"pii\"}")).isFalse();
        assertThat(SafetyKeyRefService.hasInlineSecret("not json")).isFalse();
    }

    private static SafetyKeyRefDO ref(String code, String salt, int status) {
        SafetyKeyRefDO ref = new SafetyKeyRefDO();
        ref.setKeyCode(code);
        ref.setExternalKeyId(salt);
        ref.setProvider(SafetyKeyRefDO.PROVIDER_KEY_CENTER);
        ref.setKeyType(SafetyKeyRefDO.KEY_TYPE_SM4);
        ref.setStatus(status);
        return ref;
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
