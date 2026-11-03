package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class QualityWorkOrderStatisticsTest {
    @Mock private QualityWorkOrderMapper workOrderMapper;
    @Mock private QualityWorkOrderGuard guard;
    @Mock private AdminUserApi adminUserApi;
    @InjectMocks private QualityWorkOrderServiceImpl service;

    @Test void statisticsMustReturnActualOwnerAggregation() {
        var order = QualityWorkOrderDO.builder().id(1L).ownerUserId(9223372036854775807L)
                .responsibilityStatus("ASSIGNED").orderStatus("PENDING").build();
        order.setCreateTime(LocalDateTime.now());
        when(workOrderMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(List.of(order));
        lenient().when(guard.requireUserId()).thenReturn(9L);
        lenient().when(adminUserApi.getUserList(any())).thenReturn(CommonResult.success(List.of()));

        var payload = JsonUtils.parseMap(JsonUtils.toJsonString(service.getWorkOrderStatistics(7)));
        assertThat(payload).containsKey("ownerSummary");
        assertThat((List<?>) payload.get("ownerSummary")).hasSize(1);
    }

    @Test void sameNamesRemainSeparateAndMissingUsersKeepAnHonestLabel() {
        var first = order(1L, 8L); var second = order(2L, 9L); var missing = order(3L, Long.MAX_VALUE);
        var unassigned = order(4L, null);
        when(workOrderMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(List.of(first, second, missing, unassigned, order(5L, 8L)));
        when(guard.requireUserId()).thenReturn(9L);
        var a = new AdminUserRespDTO(); a.setId(8L); a.setNickname("同名");
        var b = new AdminUserRespDTO(); b.setId(9L); b.setNickname("同名");
        when(adminUserApi.getUserList(Set.of(8L, 9L, Long.MAX_VALUE))).thenReturn(CommonResult.success(List.of(a, b)));
        var stats = service.getWorkOrderStatistics(7);
        assertThat(stats.getOwnerSummary()).extracting(item -> item.ownerUserId()).containsExactly(8L, 9L, Long.MAX_VALUE, null);
        assertThat(stats.getOwnerSummary()).extracting(item -> item.ownerUserName()).containsExactly("同名", "同名", "用户（9223372036854775807）", "未认责");
        assertThat(stats.getOwnerSummary()).extracting(item -> item.count()).containsExactly(2, 1, 1, 1);
        assertThat(stats.getTotalCount()).isEqualTo(5);
        verify(guard).applyVisibleScope(any(), org.mockito.ArgumentMatchers.eq(9L));
    }

    @Test void windowDefaultsCapsAndEmptyCollectionsStayExplicit() {
        when(workOrderMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(List.of());
        when(guard.requireUserId()).thenReturn(9L);
        for (Integer days : new Integer[]{null, 0, -1, 1, 90, 999}) {
            int expected = days == null || days <= 0 ? 30 : Math.min(days, 90);
            var stats = service.getWorkOrderStatistics(days);
            assertThat(stats.getWindow()).isEqualTo(expected);
            assertThat(stats.getTrend()).hasSize(expected);
            assertThat(stats.getOwnerSummary()).isEmpty();
            assertThat(stats.getTotalCount()).isZero();
            assertThat(stats.getSampleStatus()).isEqualTo("NO_SAMPLE");
        }
        verify(adminUserApi, never()).getUserList(any());
    }

    @Test void wireContractUsesActualPlatformLongAndTimestampSerializers() {
        when(workOrderMapper.selectList(any(LambdaQueryWrapperX.class))).thenReturn(List.of(order(1L, 8L), order(2L, Long.MAX_VALUE)));
        when(guard.requireUserId()).thenReturn(9L);
        when(adminUserApi.getUserList(any())).thenReturn(CommonResult.success(List.of()));
        var stats = service.getWorkOrderStatistics(7);
        var mapper = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        var json = mapper.readTree(mapper.writeValueAsBytes(CommonResult.success(stats)));
        var payload = json.path("data");
        assertThat(json.path("code").asInt()).isZero();
        assertThat(payload.path("ownerSummary").get(0).path("ownerUserId").isNumber()).isTrue();
        assertThat(payload.path("ownerSummary").get(1).path("ownerUserId").asString()).isEqualTo("9223372036854775807");
        assertThat(payload.path("asOf").isNumber()).isTrue();
        assertThat(payload.path("trend").get(0).path("date").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(payload.path("totalCount").asInt()).isEqualTo(2);
    }

    private QualityWorkOrderDO order(Long id, Long owner) {
        var order = QualityWorkOrderDO.builder().id(id).ownerUserId(owner)
                .responsibilityStatus(owner == null ? "UNASSIGNED" : "ASSIGNED").orderStatus("PENDING").build();
        order.setCreateTime(LocalDateTime.now().minusMinutes(1));
        return order;
    }
}
