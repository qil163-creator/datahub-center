package cn.iocoder.yudao.module.asset.service.dataasset;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import cn.iocoder.yudao.module.asset.service.market.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AssetSupplyStopServiceTest extends BaseMockitoUnitTest {
    @Mock DataAssetMapper assets;
    @Mock AssetSupplyStopMapper stops;
    @Mock AssetCatalogService catalog;
    @Mock MarketApplyService applies;
    @Mock MarketAuditRecorder audit;
    @Mock PlatformTransactionManager transactionManager;
    @InjectMocks AssetSupplyStopService service;
    @BeforeEach void tenant() { TenantContextHolder.clear(); }
    @AfterEach void clear() { TenantContextHolder.clear(); }
    @Test void oneFailedRevocationCannotDropRemainingStopIntent() {
        when(stops.due()).thenReturn(List.of(7L,8L));
        doThrow(new IllegalStateException("transient")).when(applies).revokeForAssetStop(7L);
        assertThrows(IllegalStateException.class,()->service.reconcileDue());
        verify(applies).revokeForAssetStop(8L);
    }
    @Test void orphanedActiveSourcePreventsDeletionEvenWhenApplicationsAppearClosed() {
        when(stops.unclosedSources(7L)).thenReturn(1L);
        assertThrows(ServiceException.class,()->service.assertDeletable(7L));
    }
    @Test void confirmedClosedSourcesPermitDeletionCheck() { assertDoesNotThrow(()->service.assertDeletable(7L)); }
    @Test void stopRequiresReasonBeforeAnySideEffect() {
        assertThrows(ServiceException.class,()->service.request(7L," "));
        verifyNoInteractions(assets,applies,audit);
    }
}
