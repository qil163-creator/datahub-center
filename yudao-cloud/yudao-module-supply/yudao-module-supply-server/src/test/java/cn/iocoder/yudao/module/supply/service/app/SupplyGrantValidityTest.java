package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyGrantValidityTest {

    @ParameterizedTest
    @CsvSource(value = {"NULL,NULL,true", "'',NULL,true", "' ',NULL,true", "hash,hash,true",
            "hash,NULL,false", "hash,'',false", "hash,other,false", "hash,HASH,false", "hash,' hash ',false"},
            nullValues = "NULL")
    void contractCompatibilityPreservesGatewayBlankAndExactMatchRules(String current, String approved,
            boolean expected) {
        var grant = AppApiDO.builder().approvedContractHash(approved).build();
        assertThat(SupplyGrantValidity.matchesCurrentContract(grant, current)).isEqualTo(expected);
        assertThat(SupplyGrantValidity.matchesCurrentContract(null, current)).isFalse();
    }

    @Test
    void halfOpenWindowIncludesFromExcludesTo() {
        SupplyAppDO app = SupplyAppDO.builder().id(1L).appStatus(SupplyGrantValidity.APP_ENABLED).build();
        AppApiDO grant = AppApiDO.builder()
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE)
                .foreverFlag(0)
                .validFrom(LocalDateTime.of(2026, 9, 16, 10, 0))
                .validTo(LocalDateTime.of(2026, 9, 16, 12, 0))
                .build();
        assertThat(SupplyGrantValidity.isEffective(app, grant, LocalDateTime.of(2026, 9, 16, 10, 0))).isTrue();
        assertThat(SupplyGrantValidity.isEffective(app, grant, LocalDateTime.of(2026, 9, 16, 11, 59))).isTrue();
        assertThat(SupplyGrantValidity.isEffective(app, grant, LocalDateTime.of(2026, 9, 16, 12, 0))).isFalse();
        assertThat(SupplyGrantValidity.isEffective(app, grant, LocalDateTime.of(2026, 9, 16, 9, 59))).isFalse();
    }

    @Test
    void twoNullsAreNotForever() {
        SupplyAppDO app = SupplyAppDO.builder().id(1L).appStatus(SupplyGrantValidity.APP_ENABLED).build();
        AppApiDO grant = AppApiDO.builder()
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE)
                .foreverFlag(0)
                .build();
        assertThat(SupplyGrantValidity.isEffective(app, grant, LocalDateTime.of(2026, 9, 16, 10, 0))).isFalse();
    }

    @Test
    void explicitForeverAllowsNullBounds() {
        SupplyAppDO app = SupplyAppDO.builder().id(1L).appStatus(SupplyGrantValidity.APP_ENABLED).build();
        AppApiDO grant = AppApiDO.builder()
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE)
                .foreverFlag(1)
                .build();
        assertThat(SupplyGrantValidity.isEffective(app, grant, LocalDateTime.of(2026, 9, 16, 10, 0))).isTrue();
    }

    @Test
    void pausedAppOrRevokedGrantIsNotEffective() {
        SupplyAppDO paused = SupplyAppDO.builder().id(1L).appStatus(SupplyGrantValidity.APP_PAUSED).build();
        AppApiDO grant = AppApiDO.builder()
                .grantStatus(SupplyGrantValidity.STATUS_ACTIVE)
                .foreverFlag(1)
                .build();
        assertThat(SupplyGrantValidity.isEffective(paused, grant, LocalDateTime.now())).isFalse();

        SupplyAppDO app = SupplyAppDO.builder().id(1L).appStatus(SupplyGrantValidity.APP_ENABLED).build();
        AppApiDO revoked = AppApiDO.builder()
                .grantStatus(SupplyGrantValidity.STATUS_REVOKED)
                .foreverFlag(1)
                .build();
        assertThat(SupplyGrantValidity.isEffective(app, revoked, LocalDateTime.now())).isFalse();
    }
}
