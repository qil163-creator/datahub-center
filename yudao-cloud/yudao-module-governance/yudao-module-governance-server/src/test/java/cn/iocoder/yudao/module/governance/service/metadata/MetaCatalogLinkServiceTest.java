package cn.iocoder.yudao.module.governance.service.metadata;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.api.metadata.dto.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class MetaCatalogLinkServiceTest extends BaseMockitoUnitTest {
    @Mock MetaCatalogLinkReceiptMapper receipts;
    @Mock MetaTableMapper tables;
    @InjectMocks MetaCatalogLinkService service;
    @BeforeEach void prepare() {
        TenantContextHolder.clear();
        lenient().when(receipts.advance(any())).thenReturn(1);
        lenient().when(receipts.insert(any(),any())).thenReturn(1);
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }
    @Test void duplicateReturnsOriginalReceiptWithoutMutatingMetadata() {
        var command = new MetadataCatalogLinkCommandDTO("op","source",1L,9L,true);
        var receipt = new MetadataCatalogLinkResultDTO("op",DigestUtil.sha256Hex(JsonUtils.toJsonString(command)),1L,"APPLIED",null);
        when(receipts.receipt("op")).thenReturn(receipt);
        assertSame(receipt, service.apply(command));
        verifyNoInteractions(tables);
        verify(receipts,never()).advance(any());
    }
    @Test void lateLinkCannotUndoNewerUnlinkTombstone() {
        when(receipts.lockTable(9L)).thenReturn(new MetaTableDO());
        when(receipts.revision("source",9L)).thenReturn(3L);
        var result=service.apply(new MetadataCatalogLinkCommandDTO("old","source",2L,9L,true));
        assertEquals("REJECTED",result.getOutcome());
        assertEquals("STALE_SOURCE_REVISION",result.getReason());
        verifyNoInteractions(tables);
        verify(receipts,never()).advance(any());
    }
    @Test void competingCatalogIsRejectedAndNewerSourceVersionIsFenced() {
        var table=new MetaTableDO(); table.setCatalogLinked(1); table.setCatalogItemUrn("owner");
        when(receipts.lockTable(9L)).thenReturn(table);
        var command=new MetadataCatalogLinkCommandDTO("other","source",4L,9L,true);
        assertEquals("CATALOG_CONFLICT",service.apply(command).getReason());
        verify(receipts).advance(command);
        verifyNoInteractions(tables);
    }
    @Test void unlinkDoesNotClearAnotherCatalogOwner() {
        var table=new MetaTableDO(); table.setCatalogLinked(1); table.setCatalogItemUrn("owner");
        when(receipts.lockTable(9L)).thenReturn(table);
        assertEquals("APPLIED",service.apply(new MetadataCatalogLinkCommandDTO("unlink","source",5L,9L,false)).getOutcome());
        verifyNoInteractions(tables);
    }
    @Test void absentTableIsConfirmedUnlinkedAndKeepsVersionTombstone() {
        var command=new MetadataCatalogLinkCommandDTO("gone","source",5L,9L,false);
        assertEquals("APPLIED",service.apply(command).getOutcome());
        verify(receipts).advance(command);
        verifyNoInteractions(tables);
    }
}
