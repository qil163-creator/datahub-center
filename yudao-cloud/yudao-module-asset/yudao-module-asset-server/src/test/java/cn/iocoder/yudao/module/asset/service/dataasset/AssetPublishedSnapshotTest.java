package cn.iocoder.yudao.module.asset.service.dataasset;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class AssetPublishedSnapshotTest {
    @Test void publishedContentIsDetachedFromMutableDraft() {
        var draft = DataAssetDO.builder().id(7L).assetName("审核名称").description("审核描述").categoryId(2L).build();
        String frozen = JsonUtils.toJsonString(Map.of("format", 1, "asset", draft));
        draft.setAssetName("未审核名称");
        assertEquals("审核名称", AssetPublishedSnapshot.read(frozen, 7L).getAssetName());
        assertThrows(ServiceException.class, () -> AssetPublishedSnapshot.read(frozen, 8L));
    }
    @Test void legacyEvidenceDoesNotInventTechnicalBindingOrApproval() {
        String old = "{\"assetName\":\"历史批准名称\",\"description\":\"旧描述\",\"shareAttr\":2}";
        assertThrows(ServiceException.class, () -> AssetPublishedSnapshot.read(old, 7L));
        var view = AssetPublishedSnapshot.historical(old, 7L);
        assertEquals("历史批准名称", view.getAssetName());
        assertNull(view.getDatasourceId());
        assertNull(view.getPublishedSnapshotId());
        assertNull(view.getCategoryId());
    }
    @Test void absentOrMalformedEvidenceIsExplicitlyIncomplete() {
        assertEquals("历史目录 #7", AssetPublishedSnapshot.historical(null, 7L).getAssetName());
        assertNull(AssetPublishedSnapshot.historical("invalid", 7L).getDatasourceId());
        assertThrows(ServiceException.class, () -> AssetPublishedSnapshot.read("{}", 7L));
    }
}
