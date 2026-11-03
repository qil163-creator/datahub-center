package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import lombok.Data;

import java.util.Objects;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_CATALOG_NOT_READY;

/** 目录业务的冻结协议；旧记录不能从可变主档补造发布证据。 */
@Data
public class AssetPublishedSnapshot {

    public static final int FORMAT = 1;

    private Integer format;

    private DataAssetDO asset;

    private java.util.List<cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagRespVO> tags;

    /**
     * 读取资产发布快照的业务数据。
     */
    public static DataAssetDO read(String json, Long assetId) {
        AssetPublishedSnapshot snapshot;
        try {
            snapshot = JsonUtils.parseObject(json, AssetPublishedSnapshot.class);
        } catch (RuntimeException invalid) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "完整发布快照，请重新提交审核");
        }
        if (snapshot == null || !Objects.equals(snapshot.format, FORMAT) || snapshot.asset == null
                || !Objects.equals(snapshot.asset.getId(), assetId)) {
            throw exception(DATA_ASSET_CATALOG_NOT_READY, "完整发布快照，请重新提交审核");
        }
        return BeanUtils.toBean(snapshot.asset, DataAssetDO.class);
    }

    /** 兼容历史只展示当时已冻结的业务字段，不从当前主档补齐审核证据。 */
    public static DataAssetDO historical(String json, Long assetId) {
        DataAssetDO result = new DataAssetDO();
        result.setId(assetId);
        result.setAssetName("历史目录 #" + assetId);
        if (json == null || json.isBlank()) {
            return result;
        }
        Map<?, ?> content;
        try {
            content = JsonUtils.parseObject(json, Map.class);
        } catch (RuntimeException invalid) {
            return result;
        }
        if (content == null) {
            return result;
        }
        result.setAssetName(text(content, "assetName", result.getAssetName()));
        result.setDescription(text(content, "description", null));
        result.setShareCondition(text(content, "shareCondition", null));
        result.setShareDenyReason(text(content, "shareDenyReason", null));
        result.setDutyBasis(text(content, "dutyBasis", null));
        result.setUpdateRule(text(content, "updateRule", null));
        Object share = content.get("shareAttr");
        if (share instanceof Number value) {
            result.setShareAttr(value.intValue());
            result.setShareAttribute(value.intValue());
        }
        return result;
    }

    /**
     * 读取并转换资产发布快照中的字符串值。
     */
    private static String text(Map<?, ?> content, String key, String fallback) {
        return content.get(key) instanceof String value ? value : fallback;
    }
}
