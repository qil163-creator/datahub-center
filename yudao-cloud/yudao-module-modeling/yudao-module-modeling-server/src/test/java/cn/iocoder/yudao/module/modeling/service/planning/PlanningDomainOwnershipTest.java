package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class PlanningDomainOwnershipTest {
    @Test
    void rejectsCrossBoardSubjectEvenWhenExistingSubjectIsUnchanged() {
        var resolver = new ModelHierarchyResolver();
        var subjects = mock(SubjectDomainMapper.class);
        ReflectionTestUtils.setField(resolver, "subjectDomainMapper", subjects);
        when(subjects.selectById(20L)).thenReturn(SubjectDomainDO.builder().id(20L).dataDomainId(10L).status(0).build());
        assertDoesNotThrow(() -> resolver.validateSubjectDomain(20L, null, 10L));
        assertServiceException(() -> resolver.validateSubjectDomain(20L, null, 11L), MODEL_SUBJECT_DATA_DOMAIN_MISMATCH);
        assertServiceException(() -> resolver.validateSubjectDomain(20L, 20L, 11L), MODEL_SUBJECT_DATA_DOMAIN_MISMATCH);
        assertServiceException(() -> resolver.validateSubjectDomain(20L, null, null), MODEL_SUBJECT_DATA_DOMAIN_MISMATCH);
    }
}
