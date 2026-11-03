package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityOrgScoreMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QualityOrgScoreAcceptanceTest {

    @Mock private QualityOrgScoreMapper orgScoreMapper;
    @Mock private QualityRuleSetMapper ruleSetMapper;
    @Mock private QualityRuleSetItemMapper ruleSetItemMapper;
    @Mock private QualityTaskMapper taskMapper;
    @Mock private QualityAuditLogMapper auditLogMapper;
    @Mock private QualityIssueDataMapper issueDataMapper;
    @InjectMocks private QualityOrgScoreServiceImpl service;

    @BeforeEach
    void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "org-score"),
                QualityAuditLogDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "org-task"),
                QualityTaskDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "org-issue"),
                cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityIssueDataDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "org-score-do"),
                cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityOrgScoreDO.class);
    }

    @Test
    void t30UnrunAndErrorAreNotDefaultFullScore() {
        when(ruleSetMapper.selectById(1L)).thenReturn(QualityRuleSetDO.builder().id(1L).build());
        when(ruleSetItemMapper.selectListByRuleSetId(1L)).thenReturn(List.of(
                QualityRuleSetItemDO.builder().ruleId(10L).build(),
                QualityRuleSetItemDO.builder().ruleId(11L).build()));
        when(taskMapper.selectList(any())).thenReturn(List.of(QualityTaskDO.builder().id(2L).build()));
        when(auditLogMapper.selectList(any())).thenReturn(List.of(
                QualityAuditLogDO.builder().ruleId(10L).resultType(QualityAuditLogDO.RESULT_ERROR).build()));
        when(issueDataMapper.selectList(any())).thenReturn(List.of());

        int written = service.calculateScore(1L, "202601", "202601");
        assertThat(written).isEqualTo(0);
        verify(orgScoreMapper).insertBatch(org.mockito.ArgumentMatchers.argThat(list -> list.isEmpty()));
    }

    @Test
    void t30PassRateUsesExecutedPassFailDenominator() {
        when(ruleSetMapper.selectById(1L)).thenReturn(QualityRuleSetDO.builder().id(1L).build());
        when(ruleSetItemMapper.selectListByRuleSetId(1L)).thenReturn(List.of(
                QualityRuleSetItemDO.builder().ruleId(10L).build(),
                QualityRuleSetItemDO.builder().ruleId(11L).build()));
        when(taskMapper.selectList(any())).thenReturn(List.of(QualityTaskDO.builder().id(2L).build()));
        when(auditLogMapper.selectList(any())).thenReturn(List.of(
                QualityAuditLogDO.builder().ruleId(10L).resultType(QualityAuditLogDO.RESULT_PASS).build(),
                QualityAuditLogDO.builder().ruleId(11L).resultType(QualityAuditLogDO.RESULT_FAIL).build()));
        when(issueDataMapper.selectList(any())).thenReturn(List.of());

        service.calculateScore(1L, "202601", "202601");
        ArgumentCaptor<java.util.List> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(orgScoreMapper).insertBatch(captor.capture());
        assertThat(captor.getValue()).isNotEmpty();
        Object first = captor.getValue().getFirst();
        assertThat(first).extracting("scoreRate").isEqualTo(new BigDecimal("50.00"));
        assertThat(first).extracting("totalRuleCount").isEqualTo(2L);
    }

}
