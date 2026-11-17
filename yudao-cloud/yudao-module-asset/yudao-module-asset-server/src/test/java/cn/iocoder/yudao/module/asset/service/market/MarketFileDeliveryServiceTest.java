package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFileBinding;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketFileBindingMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.infra.enums.ErrorCodeConstants.FILE_TYPE_NOT_ALLOWED;
import static cn.iocoder.yudao.module.infra.enums.ErrorCodeConstants.MANAGED_FILE_CONFLICT;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketFileDeliveryServiceTest {
    private final MarketFileBindingMapper bindings = mock(MarketFileBindingMapper.class);
    private final AssetCatalogService catalog = mock(AssetCatalogService.class);
    private final ManagedFileApi storage = mock(ManagedFileApi.class);
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final MarketObjectGuard guard = mock(MarketObjectGuard.class);
    private final MarketMyAssetService myAssets = mock(MarketMyAssetService.class);
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final MarketFileDeliveryService service = new MarketFileDeliveryService(assets, bindings, catalog, guard, storage, tx);
    private final MarketFileDownloadService downloader = new MarketFileDownloadService(myAssets, bindings, assets, storage, tx);
    private final DataAssetDO asset = DataAssetDO.builder().id(5L).assetType(DataAssetDO.TYPE_FILE)
            .status(DataAssetDO.STATUS_PUBLISHED).publishedVersion("v1").publishedCatalogVersionId(12L).build();
    private final AtomicReference<MarketFileBinding> slot = new AtomicReference<>();
    private final MockMultipartFile valid = file("交付.csv", "a,b\n1,2\n");

    @BeforeEach
    void setUp() {
        when(assets.selectById(5L)).thenReturn(asset);
        when(assets.selectForCatalogUpdate(5L)).thenReturn(asset);
        when(guard.requireUserId()).thenReturn(9L);
        when(bindings.reserve(any())).thenAnswer(call -> {
            MarketFileBinding intent = call.getArgument(0);
            var pending = new MarketFileBinding();
            pending.setCatalogVersionId(intent.getCatalogVersionId());
            pending.setAssetId(intent.getAssetId());
            pending.setOperationKey(intent.getOperationKey());
            pending.setSha256(intent.getSha256());
            pending.setFileName(intent.getFileName());
            pending.setFileSize(intent.getFileSize());
            return slot.compareAndSet(null, pending) ? 1 : 0;
        });
        when(bindings.find(12L)).thenAnswer(call -> slot.get());
        when(bindings.complete(any())).thenAnswer(call -> {
            slot.set(call.getArgument(0)); return 1;
        });
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(9L), null, List.of()));
    }

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusedFileThenDifferentValidFileSucceedsAndAcceptedBindingIsImmutable() throws IOException {
        when(storage.createManaged(any())).thenReturn(CommonResult.error(FILE_TYPE_NOT_ALLOWED), accepted());
        assertThatThrownBy(() -> upload(file("无效.exe", "invalid"))).isInstanceOf(ServiceException.class);
        assertThat(service.getBinding(5L, 12L)).isNull();
        var binding = upload(valid);
        assertThat(binding.fileName()).isEqualTo(valid.getOriginalFilename());
        assertThat(binding.sha256()).isEqualTo(DigestUtil.sha256Hex(valid.getBytes()));
        assertThat(upload(valid)).isEqualTo(binding);
        assertThatThrownBy(() -> upload(file("替换.csv", "changed")))
                .isInstanceOf(ServiceException.class).extracting("code").isEqualTo(MARKET_FILE_CONFLICT.getCode());
        verify(storage, times(2)).createManaged(argThat(req -> "asset_market_file_5_12".equals(req.getOperationKey())));
        verifyNoMoreInteractions(storage);
    }

    @Test
    void uncertainWriteUsesSameRemoteIdentityAndCannotBeReplacedByAConflictingFile() throws IOException {
        when(storage.createManaged(any())).thenThrow(new IllegalStateException("connection lost"))
                .thenReturn(CommonResult.error(MANAGED_FILE_CONFLICT), accepted());
        assertThatThrownBy(() -> upload(valid)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> upload(file("其他.csv", "different"))).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(MARKET_FILE_CONFLICT.getCode());
        assertThat(service.getBinding(5L, 12L)).isNull();
        assertThat(upload(valid).fileName()).isEqualTo(valid.getOriginalFilename());
        verify(storage, times(3)).createManaged(argThat(req -> "asset_market_file_5_12".equals(req.getOperationKey())));
        verifyNoMoreInteractions(storage);
    }

    @Test
    void mismatchedStorageReceiptNeverBecomesAnApprovedBinding() throws IOException {
        var incorrect = accepted().getCheckedData();
        incorrect.setSha256("0".repeat(64));
        when(storage.createManaged(any())).thenReturn(CommonResult.success(incorrect));
        assertThatThrownBy(() -> upload(valid)).isInstanceOf(ServiceException.class);
        verify(bindings, never()).complete(any());
        assertThatThrownBy(() -> service.requirePublishedBinding(asset)).isInstanceOf(ServiceException.class);
    }

    @Test
    void frozenGrantTracksItsVersionFileIdentityAndWholeFileScope() throws IOException {
        when(storage.createManaged(any())).thenReturn(accepted());
        upload(valid);
        var apply = fileApply();
        var snapshot = new HashMap<String,Object>(Map.of("assetId","5","catalogVersionId","12","assetVersion","v1"));
        service.freeze(apply, asset, snapshot);
        apply.setSnapshot(JsonUtils.toJsonString(snapshot));
        service.requireGrant(apply, asset);
        assertThat(MarketFileDeliveryService.frozenMetadata(apply).fileName()).isEqualTo(valid.getOriginalFilename());
        asset.setPublishedCatalogVersionId(13L);
        assertThatThrownBy(() -> service.requireGrant(apply, asset)).isInstanceOf(ServiceException.class);
        asset.setPublishedCatalogVersionId(12L);
        slot.get().setSha256("0".repeat(64));
        assertThatThrownBy(() -> service.requireGrant(apply, asset)).isInstanceOf(ServiceException.class);
        apply.setFieldScope("column_a");
        assertThatThrownBy(() -> service.freeze(apply, asset, snapshot)).isInstanceOf(ServiceException.class);
        apply.setFieldScope(null);
        apply.setApplyUseType(MarketResourceApplyDO.USE_ROWS);
        assertThatThrownBy(() -> service.freeze(apply, asset, snapshot)).isInstanceOf(ServiceException.class);
    }

    @Test
    void fileDownloadLockQueriesPassTheApplicationSqlParser() throws Exception {
        var locks = List.of(MarketFileBindingMapper.class.getMethod("lockDownloadAssetId", long.class, long.class),
                DataAssetMapper.class.getMethod("selectForMatching", long.class));
        for (var method : locks) {
            String sql = String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("#\\{[^}]+}", "?");
            assertThat(CCJSqlParserUtil.parse(sql)).isNotNull();
        }
    }

    @Test
    void downloadedBytesMatchApprovedFingerprintAndQuotaIsConsumedOnlyAfterFinalCheck() throws IOException {
        var grant = prepareDownload();
        when(myAssets.authorizeFileDownload(7L)).thenReturn(grant);
        when(bindings.consume(7L, 1L)).thenReturn(1,0);
        var downloaded = downloader.download(7L);
        assertThat(downloaded.content()).containsExactly(valid.getBytes());
        assertThat(downloaded.name()).isEqualTo(valid.getOriginalFilename());
        assertThatThrownBy(() -> downloader.download(7L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(MARKET_FILE_LIMIT.getCode());
    }

    @Test
    void permissionChangeDuringReadDiscardsBytesAndPreservesQuota() throws IOException {
        var grant = prepareDownload();
        when(myAssets.authorizeFileDownload(7L)).thenReturn(grant)
                .thenThrow(new ServiceException(MARKET_APPLY_NOT_AUTHORIZED));
        assertThatThrownBy(() -> downloader.download(7L)).isInstanceOf(ServiceException.class);
        verify(storage).readManaged(70L, "asset_market_file_5_12");
        verify(bindings, never()).consume(anyLong(), any());
    }

    @Test
    void corruptedStorageAndForeignOwnersCannotConsumeOrReceiveFile() throws IOException {
        var grant = prepareDownload();
        when(myAssets.authorizeFileDownload(7L)).thenReturn(grant);
        when(storage.readManaged(70L, "asset_market_file_5_12")).thenReturn(CommonResult.success(new byte[]{1}));
        assertThatThrownBy(() -> downloader.download(7L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(MARKET_FILE_UNAVAILABLE.getCode());
        grant.setApplyUserId(10L);
        assertThatThrownBy(() -> downloader.download(7L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(MARKET_APPLY_NOT_AUTHORIZED.getCode());
        verify(storage, times(1)).readManaged(anyLong(), anyString());
        verify(bindings, never()).consume(anyLong(), any());
    }

    private MarketResourceApplyDO prepareDownload() throws IOException {
        when(storage.createManaged(any())).thenReturn(accepted());
        upload(valid);
        var grant = fileApply();
        grant.setSnapshot("{\"applyUseType\":\"1\",\"applyUseTimes\":\"1\"}");
        when(storage.readManaged(70L, "asset_market_file_5_12")).thenReturn(CommonResult.success(valid.getBytes()));
        when(bindings.lockDownloadAssetId(7L, 9L)).thenReturn(5L);
        when(assets.selectForMatching(5L)).thenReturn(asset);
        return grant;
    }

    private MarketResourceApplyDO fileApply() {
        return MarketResourceApplyDO.builder().id(7L).applyUserId(9L).assetId(5L).assetType(DataAssetDO.TYPE_FILE)
                .catalogVersionId(12L).sourceRevision(2L).revision(1).applyUseType(1).applyUseTimes(1).build();
    }

    private CommonResult<ManagedFileRespDTO> accepted() throws IOException {
        var ready = new ManagedFileRespDTO();
        ready.setId(70L);
        ready.setState("READY");
        ready.setSha256(DigestUtil.sha256Hex(valid.getBytes()));
        ready.setSize(valid.getSize());
        return CommonResult.success(ready);
    }

    private cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketFileBindingRespVO upload(MockMultipartFile input) throws IOException {
        return service.upload(5L, 12L, input);
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content.getBytes(StandardCharsets.UTF_8));
    }
}
