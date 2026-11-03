package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDeliveryOperationRespVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDeliveryActionContractTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();

    @ParameterizedTest
    @ValueSource(strings = {"GRANT", "SUSPEND", "REVOKE", "UNKNOWN"})
    void additiveActionPreservesTechnicalFieldsLosslessIdsAndTimestamp(String action) {
        var value = new MarketDeliveryOperationRespVO(9007199254740993L, "original-operation", 7L,
                "REVOKE", 9007199254740994L, "PENDING", "UNCONFIRMED", 0, null, null,
                LocalDateTime.of(2026, 9, 28, 1, 0), 2, action);
        var json = mapper.readTree(mapper.writeValueAsBytes(value));
        assertThat(json.get("action").asString()).isEqualTo(action);
        assertThat(json.get("opType").asString()).isEqualTo("REVOKE");
        assertThat(json.get("status").asString()).isEqualTo("PENDING");
        assertThat(json.get("resolutionStatus").asString()).isEqualTo("UNCONFIRMED");
        assertThat(json.get("id").isString()).isTrue();
        assertThat(json.get("sourceRevision").isString()).isTrue();
        assertThat(json.get("id").asString()).isEqualTo("9007199254740993");
        assertThat(json.get("sourceRevision").asString()).isEqualTo("9007199254740994");
        assertThat(json.get("applyId").isNumber()).isTrue();
        assertThat(json.get("updateTime").isNumber()).isTrue();
        assertThat(json.has("payloadJson")).isFalse();
        assertThat(json.has("payloadHash")).isFalse();
        assertThat(json.has("scope")).isFalse();
    }

    @Test
    void suspendedResolutionIsSeparateFromApprovalAndAccess() {
        var value = new MarketApplyRespVO();
        value.setDeliveryResolution("SUSPENDED");
        value.setEffective(false);
        value.setUsable(false);
        var json = mapper.readTree(mapper.writeValueAsBytes(value));
        assertThat(json.get("deliveryResolution").asString()).isEqualTo("SUSPENDED");
        assertThat(json.get("effective").asBoolean()).isFalse();
        assertThat(json.get("usable").asBoolean()).isFalse();
    }
}
