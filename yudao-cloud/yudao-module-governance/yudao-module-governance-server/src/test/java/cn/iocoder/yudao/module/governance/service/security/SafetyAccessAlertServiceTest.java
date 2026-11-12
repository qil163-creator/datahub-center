package cn.iocoder.yudao.module.governance.service.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyAccessAlertHandleReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyAccessAlertDO;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyAccessAlertMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyAccessUserStat;
import cn.iocoder.yudao.module.governance.framework.security.SafetyAccessAlertProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异常访问识别：窗口对齐、阈值规则、重跑幂等、处理 CAS。
 */
class SafetyAccessAlertServiceTest {

    private SafetyAccessAlertMapper mapper;
    private SafetyAccessAlertService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SafetyAccessAlertMapper.class);
        SafetyAccessAlertProperties properties = new SafetyAccessAlertProperties();
        properties.setVolumeThreshold(100);
        properties.setPlaintextThreshold(10);
        properties.setOffHoursThreshold(5);
        properties.setTableSpreadThreshold(0);
        service = new SafetyAccessAlertService();
        ReflectionTestUtils.setField(service, "alertMapper", mapper);
        ReflectionTestUtils.setField(service, "properties", properties);
    }

    @Test
    void detectUsesTheLastCompletedAlignedWindow() {
        when(mapper.selectUserStats(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        service.detect(LocalDateTime.of(2026, 11, 12, 10, 17, 3));
        verify(mapper).selectUserStats(eq(LocalDateTime.of(2026, 11, 12, 9, 0)),
                eq(LocalDateTime.of(2026, 11, 12, 10, 0)), eq(22), eq(6));
    }

    @Test
    void eachExceededRuleRaisesOneDedupedAlertAndDisabledRulesAreSkipped() {
        SafetyAccessUserStat stat = stat(150, 12, 2, 500);
        List<SafetyAccessAlertDO> alerts = service.evaluate(stat, LocalDateTime.of(2026, 11, 12, 9, 0),
                LocalDateTime.of(2026, 11, 12, 10, 0));
        assertThat(alerts).extracting(SafetyAccessAlertDO::getRuleCode).containsExactly("VOLUME", "PLAINTEXT");
        assertThat(alerts.getFirst().getDedupeKey()).isEqualTo("VOLUME:7:2026-11-12T09:00");
        assertThat(alerts.getFirst().getMetricValue()).isEqualTo(150);
        assertThat(alerts.getFirst().getThreshold()).isEqualTo(100);
        assertThat(alerts.getFirst().getStatus()).isEqualTo("OPEN");
    }

    @Test
    void rerunningTheSameWindowIsIdempotent() {
        when(mapper.selectUserStats(any(), any(), anyInt(), anyInt())).thenReturn(List.of(stat(150, 0, 0, 1)));
        when(mapper.insert(any(SafetyAccessAlertDO.class))).thenReturn(1)
                .thenThrow(new DuplicateKeyException("uk"));
        LocalDateTime from = LocalDateTime.of(2026, 11, 12, 9, 0);
        assertThat(service.detectWindow(from, from.plusHours(1))).isEqualTo(1);
        assertThat(service.detectWindow(from, from.plusHours(1))).isZero();
        ArgumentCaptor<SafetyAccessAlertDO> captor = ArgumentCaptor.forClass(SafetyAccessAlertDO.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(SafetyAccessAlertDO::getDedupeKey).containsOnly(
                "VOLUME:7:2026-11-12T09:00");
    }

    @Test
    void handlingAnAlreadyHandledAlertIsRejected() {
        when(mapper.selectById(1L)).thenReturn(new SafetyAccessAlertDO());
        when(mapper.handle(eq(1L), eq(0), anyString(), any(), anyString(), any())).thenReturn(0);
        SafetyAccessAlertHandleReqVO req = new SafetyAccessAlertHandleReqVO();
        req.setId(1L);
        req.setStatus("IGNORED");
        req.setRemark("批量对账");
        req.setExpectedRevision(0);
        assertThatThrownBy(() -> service.handle(req)).isInstanceOf(ServiceException.class);
        when(mapper.selectById(2L)).thenReturn(null);
        req.setId(2L);
        assertThatThrownBy(() -> service.handle(req)).isInstanceOf(ServiceException.class);
    }

    private static SafetyAccessUserStat stat(int total, int plaintext, int offHours, int tables) {
        SafetyAccessUserStat stat = new SafetyAccessUserStat();
        stat.setUserId(7L);
        stat.setUserName("张三");
        stat.setTotal(total);
        stat.setPlaintext(plaintext);
        stat.setOffHours(offHours);
        stat.setTables(tables);
        return stat;
    }
}
