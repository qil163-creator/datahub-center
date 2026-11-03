package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataApiSignatureV2Test {

    @Test
    void changingProtectedFieldsMustChangeSignature() {
        byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
        String baseline = DataApiSignatureV2.sign("secret", "POST", "/services/v1/orders",
                "pageNo=1&pageSize=20", body, "app-1", "1720000000000", "nonce-1",
                "application/json", "1");

        assertThat(DataApiSignatureV2.sign("secret", "GET", "/services/v1/orders",
                "pageNo=1&pageSize=20", body, "app-1", "1720000000000", "nonce-1",
                "application/json", "1")).isNotEqualTo(baseline);
        assertThat(DataApiSignatureV2.sign("secret", "POST", "/services/v1/other",
                "pageNo=1&pageSize=20", body, "app-1", "1720000000000", "nonce-1",
                "application/json", "1")).isNotEqualTo(baseline);
        assertThat(DataApiSignatureV2.sign("secret", "POST", "/services/v1/orders",
                "pageNo=1&pageSize=21", body, "app-1", "1720000000000", "nonce-1",
                "application/json", "1")).isNotEqualTo(baseline);
        assertThat(DataApiSignatureV2.sign("secret", "POST", "/services/v1/orders",
                "pageNo=1&pageSize=20", "{\"id\":\"2\"}".getBytes(StandardCharsets.UTF_8),
                "app-1", "1720000000000", "nonce-1", "application/json", "1"))
                .isNotEqualTo(baseline);
    }

    @Test
    void chineseAndEmptyBodyHaveDeterministicCanonicalForm() {
        LinkedHashMap<String, String> query = DataApiQueryCodec.parse("q=%E6%B5%8B%20%26%2B");
        assertThat(query.get("q")).isEqualTo("测 &+");
        assertThat(DataApiQueryCodec.canonicalize(query)).isEqualTo("q=%E6%B5%8B%20%26%2B");
        String empty = DataApiSignatureV2.bodyHash(new byte[0]);
        String alsoEmpty = DataApiSignatureV2.bodyHash(null);
        assertThat(empty).isEqualTo(alsoEmpty).hasSize(64);
    }

    @Test
    void rejectsAmbiguousQueryAndPath() {
        assertThatThrownBy(() -> DataApiQueryCodec.parse("a=1&a=2"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiQueryCodec.parse("a=%ZZ"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiSignatureV2.validatePath("/services/v1/../secret"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiSignatureV2.validateNonce("bad nonce"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DataApiSignatureV2.validateTimestamp("1720000000"))
                .isInstanceOf(ServiceException.class);
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
