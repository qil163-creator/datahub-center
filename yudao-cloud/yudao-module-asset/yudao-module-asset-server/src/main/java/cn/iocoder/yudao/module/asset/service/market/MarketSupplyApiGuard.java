package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_API_ASSET_MISMATCH;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_API_NOT_EXISTS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_API_TYPE_UNSUPPORTED;

/**
 * 申请开通前核验 Supply API 是否为可证明的已上线单表查询服务。
 */
@Slf4j
@Component
public class MarketSupplyApiGuard {

    @Resource
    private SupplyDataApiApi supplyDataApiApi;

    /**
     * 校验市场供数API的可查询表API，不满足约束时拒绝当前操作。
     */
    public SupplyApiBindingRespDTO requireQueryableTableApi(Long apiId) {
        SupplyApiBindingRespDTO binding;
        try {
            binding = supplyDataApiApi.getApiBinding(apiId).getCheckedData();
        } catch (RuntimeException ex) {
            log.warn("[requireQueryableTableApi] 查询 API 失败 apiId={} msg={}", apiId, ex.getMessage());
            throw exception(MARKET_APPLY_API_NOT_EXISTS);
        }
        if (binding == null || binding.getId() == null) {
            throw exception(MARKET_APPLY_API_NOT_EXISTS);
        }
        if (!Objects.equals(binding.getStatus(), 1)
                || !StrUtil.equalsIgnoreCase(binding.getApiType(), "QUERY")
                || !StrUtil.equalsIgnoreCase(binding.getConfigType(), "TABLE")) {
            throw exception(MARKET_APPLY_API_TYPE_UNSUPPORTED);
        }
        return binding;
    }

    /**
     * 校验市场供数API的匹配资产，不满足约束时拒绝当前操作。
     */
    public void requireMatchesAsset(DataAssetDO asset, SupplyApiBindingRespDTO binding) {
        if (asset == null || binding == null
                || !Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)
                || !Objects.equals(asset.getDatasourceId(), binding.getDatabaseId())
                || !StrUtil.equalsIgnoreCase(asset.getTableName(), binding.getTableName())) {
            throw exception(MARKET_APPLY_API_ASSET_MISMATCH);
        }
    }

}
