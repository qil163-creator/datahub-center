package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketResourceRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketListingDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketListingMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCategoryMapper;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MarketDisplayHelper {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    @Resource
    private AssetUserNameResolver userNameResolver;

    @Resource
    private DeptApi deptApi;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketListingMapper listingMapper;

    @Resource
    private MarketCategoryMapper categoryMapper;

    /** 卡片与详情统一使用集市展示信息；没有市场记录时仅回退已传入的冻结资产视图。 */
    public void fillListings(List<MarketResourceRespVO> assets) {
        if (assets.isEmpty()) {
            return;
        }
        var listings = listingMapper.selectByAssetIds(assets.stream().map(MarketResourceRespVO::getId).toList())
                .stream()
                .collect(Collectors.toMap(MarketListingDO::getAssetId, Function.identity()));
        var categoryIds = listings.values().stream().map(MarketListingDO::getMarketCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var names = categoryIds.isEmpty() ? Map.<Long, String>of() : categoryMapper.selectByIds(categoryIds).stream()
                .collect(Collectors.toMap(item -> item.getId(), item -> item.getName()));
        assets.forEach(asset -> {
            var listing = listings.get(asset.getId());
            asset.setMarketName(listing == null ? asset.getAssetName() : listing.getDisplayName());
            asset.setMarketDescription(listing == null ? asset.getDescription() : listing.getDescription());
            asset.setMarketCategoryId(listing == null ? null : listing.getMarketCategoryId());
            asset.setMarketCategoryName(listing == null ? null : names.get(listing.getMarketCategoryId()));
            asset.setFrequency(listing == null ? null : listing.getFrequency());
            asset.setDeliveryTypes(listing == null ? List.of() : listingDeliveryTypes(listing.getDeliveryTypes()));
            asset.setSourceRecordId(listing == null ? null : listing.getSourceRecordId());
            asset.setFileName(listing == null ? null : listing.getFileName());
            asset.setFileType(listing == null ? null : listing.getFileType());
            asset.setServiceIdentifier(listing == null ? null : listing.getServiceIdentifier());
            asset.setPrimaryDeliveryType(listing == null ? null : listing.getPrimaryDeliveryType());
            asset.setDisplayScore(listing == null ? null : listing.getDisplayScore());
        });
    }

    /**
     * 整理市场上架记录声明的交付方式。
     */
    public static List<Integer> listingDeliveryTypes(String value) {
        if (value == null || !value.matches("1|2|3|1,2|1,3|2,3|1,2,3")) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(Integer::valueOf).toList();
    }

    /**
     * 批量解析用户编号并建立用户显示名称映射。
     */
    public Map<Long, String> userNames(Collection<Long> userIds) {
        return userNameResolver.resolve(userIds);
    }

    /**
     * 批量解析部门编号并建立部门显示名称映射。
     */
    public Map<Long, String> deptNames(Collection<Long> deptIds) {
        Set<Long> ids = deptIds == null ? Set.of() : deptIds.stream()
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            List<DeptRespDTO> depts = deptApi.getDeptList(ids).getCheckedData();
            return depts.stream().collect(Collectors.toMap(DeptRespDTO::getId,
                    dept -> StrUtil.blankToDefault(dept.getName(), String.valueOf(dept.getId())),
                    (a, b) -> a));
        } catch (RuntimeException ex) {
            log.warn("[deptNames] 部门服务不可用：{}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 批量读取资产并按资产编号建立索引。
     */
    public Map<Long, DataAssetDO> assets(Collection<Long> assetIds) {
        Set<Long> ids = assetIds == null ? Set.of() : assetIds.stream()
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return assetMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(DataAssetDO::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * 从输入集合提取有效编号并去重。
     */
    public List<Long> ids(Long... values) {
        return values == null ? List.of() : Arrays.stream(values).filter(Objects::nonNull).distinct().toList();
    }

    /**
     * 批量解析用户编号并建立用户显示名称映射。
     */
    public Map<Long, String> userNames(Long... userIds) {
        if (userIds == null || userIds.length == 0) {
            return Collections.emptyMap();
        }
        return userNames(Arrays.asList(userIds));
    }

    /**
     * 批量解析部门编号并建立部门显示名称映射。
     */
    public Map<Long, String> deptNames(Long... deptIds) {
        if (deptIds == null || deptIds.length == 0) {
            return Collections.emptyMap();
        }
        return deptNames(Arrays.asList(deptIds));
    }

    /**
     * 从用户名称映射中读取可展示的名称。
     */
    public String userLabel(Map<Long, String> names, Long userId) {
        if (userId == null) {
            return "（未指定）";
        }
        return StrUtil.blankToDefault(names.get(userId), "（未知用户）");
    }

    /**
     * 从部门名称映射中读取可展示的名称。
     */
    public String deptLabel(Map<Long, String> names, Long deptId) {
        if (deptId == null) {
            return "（未指定）";
        }
        return StrUtil.blankToDefault(names.get(deptId), "（未知部门）");
    }

    /**
     * 生成下一个业务编号。
     */
    public String nextBizNo(String prefix, long sequence) {
        return prefix + LocalDate.now().format(DAY) + String.format("%08d", sequence);
    }

    /**
     * 收集需要批量解析的有效编号。
     */
    public <T> List<Long> collect(Collection<T> list, Function<T, Long> getter) {
        if (CollUtil.isEmpty(list)) {
            return List.of();
        }
        return list.stream().map(getter).filter(Objects::nonNull).distinct().toList();
    }

}
