package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataApiSignatureTest {

    @Test
    void changingProtectedFieldsMustChangeSignature() {
        byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
        String baseline = DataApiSignature.sign("secret", "POST", "/services/v1/orders",
                "pageNo=1&pageSize=20", body, "app-1", "1720000000000", "nonce-1",
                "application/json");

        assertThat(DataApiSignature.sign("secret", "GET", "/services/v1/orders",
                "pageNo=1&pageSize=20", body, "app-1", "1720000000000", "nonce-1",
                "application/json")).isNotEqualTo(baseline);
        assertThat(DataApiSignature.sign("secret", "POST", "/services/v1/other",
                "pageNo=1&pageSize=20", body, "app-1", "1720000000000", "nonce-1",
                "application/json")).isNotEqualTo(baseline);
        assertThat(DataApiSignature.sign("secret", "POST", "/services/v1/orders",
                "pageNo=1&pageSize=21", body, "app-1", "1720000000000", "nonce-1",
                "application/json")).isNotEqualTo(baseline);
        assertThat(DataApiSignature.sign("secret", "POST", "/services/v1/orders",
                "pageNo=1&pageSize=20", "{\"id\":\"2\"}".getBytes(StandardCharsets.UTF_8),
                "app-1", "1720000000000", "nonce-1", "application/json"))
                .isNotEqualTo(baseline);
    }

    @Test
    void chineseAndEmptyBodyHaveDeterministicCanonicalForm() {
        LinkedHashMap<String, String> query = DataApiQueryCodec.parse("q=%E6%B5%8B%20%26%2B");
        assertThat(query.get("q")).isEqualTo("测 &+");
        assertThat(DataApiQueryCodec.canonicalize(query)).isEqualTo("q=%E6%B5%8B%20%26%2B");
        String empty = DataApiSignature.bodyHash(new byte[0]);
        String alsoEmpty = DataApiSignature.bodyHash(null);
        assertThat(empty).isEqualTo(alsoEmpty).hasSize(64);
    }

    @Test
    void rejectsAmbiguousQueryAndPath() {
        assertThatThrownBy(() -> DataApiQueryCodec.parse("a=1&a=2"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiQueryCodec.parse("a=%ZZ"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiSignature.validatePath("/services/v1/../secret"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiSignature.validateNonce("bad nonce"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiSignature.validateTimestamp("1720000000"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void canonicalStringStartsWithProtocolIdentifierAndHasNoVersionField() {
        String canonical = DataApiSignature.canonicalString("get", "/services/v1/orders", "b=2&a=1",
                new byte[0], "app-1", "1720000000000", "nonce-1", "application/json; charset=UTF-8");
        String[] lines = canonical.split("\n");
        assertThat(lines).hasSize(9);
        assertThat(lines[0]).isEqualTo("8:HMAC-SM3");
        assertThat(lines[1]).isEqualTo("3:GET");
        // 查询串按原始参数顺序规范化编码（不排序），客户端须按发送顺序签名。
        assertThat(lines[3]).isEqualTo("7:b=2&a=1");
    }

    @Test
    void duplicateJsonPropertiesAreRejected() {
        assertThatThrownBy(() -> DataApiJsonBody.parseObject("{\"a\":1,\"a\":2}"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void nonceTtlCoversFutureTimestampWindow() {
        long now = 1_000_000L;
        long future = now + DataApiGatewayService.SIGN_TIMESTAMP_VALID_MS - 1;
        long ttl = DataApiGatewayService.nonceTtlMillis(future, now);
        assertThat(ttl).isGreaterThanOrEqualTo(DataApiGatewayService.SIGN_TIMESTAMP_VALID_MS
                + DataApiGatewayService.CLOCK_SKEW_MARGIN_MS - 1);
        long past = now - DataApiGatewayService.SIGN_TIMESTAMP_VALID_MS;
        assertThat(DataApiGatewayService.nonceTtlMillis(past, now))
                .isGreaterThanOrEqualTo(DataApiGatewayService.CLOCK_SKEW_MARGIN_MS);
    }
}
