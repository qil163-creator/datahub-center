package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.DatabaseTypeController;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeStatusReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PutMapping;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseTypeStatusTest {

    @Test
    void updatesOnlyStatusWithoutReadingOrOverwritingSort() {
        var service = new DatabaseTypeServiceImpl();
        var mapper = mock(DatabaseTypeMapper.class);
        ReflectionTestUtils.setField(service, "databaseTypeMapper", mapper);
        when(mapper.updateById(any(DatabaseTypeDO.class))).thenReturn(1);
        var request = new DatabaseTypeStatusReqVO();
        request.setId(9_007_199_254_740_993L);
        request.setStatus((short) 1);

        service.updateDatabaseTypeStatus(request);

        var captured = ArgumentCaptor.forClass(DatabaseTypeDO.class);
        verify(mapper).updateById(captured.capture());
        assertEquals(request.getId(), captured.getValue().getId());
        assertEquals((short) 1, captured.getValue().getStatus());
        assertNull(captured.getValue().getSort());
        assertNull(captured.getValue().getTypeCode());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingRecordReturnsExistingBusinessError() {
        var service = new DatabaseTypeServiceImpl();
        var mapper = mock(DatabaseTypeMapper.class);
        ReflectionTestUtils.setField(service, "databaseTypeMapper", mapper);
        when(mapper.updateById(any(DatabaseTypeDO.class))).thenReturn(0);
        var request = new DatabaseTypeStatusReqVO();
        request.setId(1L);
        request.setStatus((short) 0);
        assertThrows(ServiceException.class, () -> service.updateDatabaseTypeStatus(request));
    }

    @Test
    void requestAndEndpointEnforceStatusAndPermissionContract() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new DatabaseTypeStatusReqVO();
            assertFalse(validator.validate(request).isEmpty());
            request.setId(1L);
            request.setStatus((short) 2);
            assertFalse(validator.validate(request).isEmpty());
            request.setStatus((short) 0);
            assertTrue(validator.validate(request).isEmpty());
            request.setStatus((short) 1);
            assertTrue(validator.validate(request).isEmpty());
        }
        var method = DatabaseTypeController.class.getMethod("updateDatabaseTypeStatus", DatabaseTypeStatusReqVO.class);
        assertArrayEquals(new String[]{"/update-status"}, method.getAnnotation(PutMapping.class).value());
        assertEquals("@ss.hasPermission('dataforge:database-type:update')", method.getAnnotation(PreAuthorize.class).value());
    }
}
