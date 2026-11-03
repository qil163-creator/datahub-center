package cn.iocoder.yudao.module.modeling.service.registration;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.api.registration.RegistrationContextApiImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationContextApiPermissionTest {
    @Test void rpcRequiresActualRegistrationPermissionBeforeReadingBusinessOptions() {
        var api=new RegistrationContextApiImpl();var security=mock(SecurityFrameworkService.class);var service=mock(RegistrationContextService.class);
        ReflectionTestUtils.setField(api,"security",security);ReflectionTestUtils.setField(api,"service",service);
        assertThrows(AccessDeniedException.class,()->api.context(10L,"ODS",null));verifyNoInteractions(service);
        when(security.hasAnyPermissions(any(String[].class))).thenReturn(true);
        api.context(10L,"ODS",null);verify(service).context(10L,"ODS",null);
    }
}
