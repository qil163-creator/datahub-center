package cn.iocoder.yudao.module.asset.service.dataasset;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetCatalogLinkOperationDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetCatalogLinkOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.market.MarketAuditRecorder;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkResultDTO;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class AssetCatalogLinkServiceTest extends BaseMockitoUnitTest {
    @Mock AssetCatalogLinkOperationMapper operations;
    @Mock DataAssetMapper assets;
    @Mock MetadataApi metadataApi;
    @Mock PlatformTransactionManager transactionManager;
    @Mock MarketAuditRecorder audit;
    @InjectMocks AssetCatalogLinkService service;
    @BeforeEach void prepare() {
        TenantContextHolder.clear();
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }
    private AssetCatalogLinkOperationDO claim(int attempts) {
        var row = new AssetCatalogLinkOperationDO();
        row.setId(10L); row.setAssetId(7L); row.setTableId(8L); row.setSourceKey("asset:7");
        row.setOperationId("op-7"); row.setSourceRevision(3L); row.setLinked(true); row.setRequestHash("hash"); row.setAttempts(attempts);
        when(operations.claim(eq(10L),anyString())).thenReturn(row);
        return row;
    }
    @Test void unknownQueryNeverBlindlyRedelivers() {
        claim(1);
        when(metadataApi.queryCatalogLinkOperation("op-7")).thenThrow(new IllegalStateException("unavailable"));
        service.deliver(10L);
        verify(metadataApi, never()).applyCatalogLink(any());
        verify(operations).complete(eq(10L),anyString(),eq("UNKNOWN"),eq("UNKNOWN"),anyString());
    }
    @Test void lostSuccessAcknowledgementIsRecoveredFromOriginalReceipt() {
        claim(2);
        when(metadataApi.queryCatalogLinkOperation("op-7")).thenReturn(CommonResult.success(new MetadataCatalogLinkResultDTO("op-7","hash",3L,"APPLIED",null)));
        service.deliver(10L);
        verify(metadataApi, never()).applyCatalogLink(any());
        verify(operations).complete(eq(10L),anyString(),eq("SUCCESS"),eq("APPLIED"),isNull());
    }
    @Test void onlyConfirmedMissingReceiptPermitsSameCommandRedelivery() {
        claim(1);
        when(metadataApi.queryCatalogLinkOperation("op-7")).thenReturn(CommonResult.success(new MetadataCatalogLinkResultDTO("op-7",null,null,"NOT_FOUND",null)));
        when(metadataApi.applyCatalogLink(any())).thenReturn(CommonResult.success(new MetadataCatalogLinkResultDTO("op-7","hash",3L,"REJECTED","CATALOG_CONFLICT")));
        service.deliver(10L);
        verify(metadataApi).applyCatalogLink(argThat(c -> "op-7".equals(c.operationId()) && c.sourceRevision()==3L));
        verify(operations).complete(eq(10L),anyString(),eq("MANUAL"),eq("REJECTED"),contains("已被其他目录占用"));
    }
    @Test void mismatchedReceiptCannotBecomeSuccessAndRetryBudgetRequiresManualReview() {
        claim(6);
        when(metadataApi.queryCatalogLinkOperation("op-7")).thenReturn(CommonResult.success(new MetadataCatalogLinkResultDTO("op-7","wrong",3L,"APPLIED",null)));
        service.deliver(10L);
        verify(operations).complete(eq(10L),anyString(),eq("MANUAL"),eq("UNKNOWN"),anyString());
    }
}
