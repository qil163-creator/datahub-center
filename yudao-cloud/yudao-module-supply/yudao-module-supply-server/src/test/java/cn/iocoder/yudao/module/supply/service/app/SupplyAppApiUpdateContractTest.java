package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiUpdateReqVO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyAppApiUpdateContractTest {

    @Test
    void decimalStringIdentifiersAndIsoDatesRemainLossless() {
        var request = JsonUtils.parseObject("""
                {"id":"9007199254740993","expectedRevision":"9007199254740995", "foreverFlag":0,
                 "startDate":"2026-10-14","endDate":"2026-10-15","description":"授权说明"}
                """, AppApiUpdateReqVO.class);
        assertThat(request.getId()).isEqualTo(9_007_199_254_740_993L);
        assertThat(request.getExpectedRevision()).isEqualTo(9_007_199_254_740_995L);
        assertThat(request.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(request.getEndDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(request.getDescription()).isEqualTo("授权说明");
    }

    @Test
    void requestCanOnlyChangeValidityAndDescription() {
        assertThat(AppApiUpdateReqVO.class.getDeclaredFields()).extracting("name")
                .containsExactlyInAnyOrder("id", "expectedRevision", "foreverFlag", "startDate", "endDate", "description");
    }

    @Test
    void requestValidationRequiresIdentityRevisionAndPermanentFlag() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new AppApiUpdateReqVO())).extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("id", "expectedRevision", "foreverFlag");
            var request = new AppApiUpdateReqVO();
            request.setId(0L); request.setExpectedRevision(-1L); request.setForeverFlag(1);
            request.setDescription("长".repeat(501));
            assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("id", "expectedRevision", "description");
            request.setId(1L); request.setExpectedRevision(0L); request.setDescription(null);
            assertThat(validator.validate(request)).isEqualTo(Set.of());
        }
    }
}
