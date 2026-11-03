package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DwLayerMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelHierarchyResolverTest {
    @Mock DwLayerMapper dwLayerMapper;
    @Mock DataDomainMapper dataDomainMapper;
    @Mock SubjectDomainMapper subjectDomainMapper;
    @InjectMocks ModelHierarchyResolver resolver;

    @BeforeEach void facts() {
        lenient().when(dwLayerMapper.selectById(1L)).thenReturn(DwLayerDO.builder().id(1L)
                .nodeType("LAYER").layerCode("DWD").status((short) 0).build());
        lenient().when(dataDomainMapper.selectById(10L)).thenReturn(DataDomainDO.builder().id(10L).status(0).build());
    }

    @ParameterizedTest @ValueSource(strings={"SOURCE","DETAIL","DIMENSION","SUMMARY","APPLICATION"})
    void directDataDomainAndLayerAreEnoughForEveryTableType(String type) {
        assertThat(resolver.evaluateOwnership(1L,null,10L,type)).isEmpty();
        assertThatCode(() -> resolver.validateOwnershipOnPublish(1L,null,10L,type)).doesNotThrowAnyException();
    }
    @Test void logicalDraftCanBeAssessedWithoutBeingPublished() {
        assertThat(resolver.evaluateOwnership(null,null,null,null))
                .extracting("ruleCode").contains("LAYER","TABLE_TYPE","DATA_DOMAIN");
    }
    @Test void disabledDomainAndMissingReferencesFailClosed() {
        when(dataDomainMapper.selectById(10L)).thenReturn(DataDomainDO.builder().id(10L).status(1).build());
        assertThat(resolver.evaluateOwnership(1L,null,10L,"DIMENSION"))
                .extracting("ruleCode").contains("DATA_DOMAIN");
        assertThat(resolver.evaluateOwnership(1L,null,999L,"SUMMARY"))
                .extracting("ruleCode").contains("DATA_DOMAIN");
    }
    @Test void disabledSubjectRemainsRejected() {
        when(subjectDomainMapper.selectById(20L)).thenReturn(SubjectDomainDO.builder().id(20L).status(1).build());
        assertThat(resolver.evaluateOwnership(1L,20L,10L,"DETAIL"))
                .extracting("ruleCode").containsExactly("SUBJECT_DOMAIN");
    }
    @Test void categoryIsNotASelectableLayer() {
        when(dwLayerMapper.selectById(1L)).thenReturn(DwLayerDO.builder().id(1L).nodeType("CATEGORY").build());
        assertThat(resolver.evaluateOwnership(1L,null,10L,"DIMENSION"))
                .extracting("ruleCode").contains("LAYER");
    }
    @Test void checkAndPublishUseIdenticalOwnershipRules() {
        var result = resolver.buildIntegrityCheck(1L,null,null,"DETAIL");
        assertThat(result.getComplete()).isFalse();
        assertThatThrownBy(() -> resolver.validateOwnershipOnPublish(1L,null,null,"DETAIL"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(result.getIssues().getFirst().getMessage());
    }
}
