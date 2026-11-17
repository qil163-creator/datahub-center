package cn.iocoder.yudao.module.governance.api.lineage;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.framework.security.LineageReportRpcProperties;
import cn.iocoder.yudao.module.governance.service.lineage.LineageReportService;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableColumnsBatchReqDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LineageApiImplTest {

    @Test
    void onlyThePinnedProducerCanWriteOnTheSecureConnection() throws Exception {
        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(new byte[]{1, 2, 3});
        var pin = VerifiedServicePeerResolver.fingerprint(certificate);
        var request = new MockHttpServletRequest();
        var service = mock(LineageReportService.class);
        var controller = new LineageApiImpl(service, mock(MetaTableKeyResolver.class),
                new LineageReportRpcProperties(Map.of("dataforge", Set.of(pin))), request);
        var report = new LineageReportReqDTO();
        request.setSecure(true);
        assertEquals(401, assertThrows(ServiceException.class, () -> controller.report(report)).getCode());
        assertEquals(401, assertThrows(ServiceException.class, () -> controller.getColumnNamesBatch(new MetadataTableColumnsBatchReqDTO())).getCode());
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        request.setSecure(false);
        assertEquals(401, assertThrows(ServiceException.class, () -> controller.report(report)).getCode());
        verifyNoInteractions(service);
        request.setSecure(true);
        controller.report(report);
        verify(service).report(report);
    }
}
