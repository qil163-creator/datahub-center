package cn.iocoder.yudao.module.asset.framework.operatelog.config;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.operatelog.core.function.CodeLabelParseFunction;
import cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.AssetTagDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.AssetTagRelDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorydataasset.CategoryDataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.categorytag.CategoryTagDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetCatalogVersionDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetReviewTicketDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataexport.DataExportRequestDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCategoryDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceCommentDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.quality.AssetQualityTaskDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.sensitive.AssetSensitiveListDO;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.AssetTagMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.assettag.AssetTagRelMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.categorydataasset.CategoryDataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.categorytag.CategoryTagMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetCatalogVersionMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetReviewTicketMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataexport.DataExportRequestMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCategoryMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMatchMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceCommentMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.quality.AssetQualityTaskMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.sensitive.AssetSensitiveListMapper;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.governance.api.standard.StandardElementApi;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardElementRespDTO;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermApi;
import cn.iocoder.yudao.module.governance.api.standardterm.dto.StandardTermRespDTO;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApi;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.function.Function;

import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.loader;
import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.via;

/**
 * 操作日志「编号 → 名称」翻译函数，模板写法如 {@code {getDataAssetName{#id}}}。
 * <p>
 * 由各业务对象的 Mapper 按编号批量读取名称；没有独立名称的记录（通知、失败记录、运行记录等）显示其所属对象的名称。
 */
@Configuration(proxyBeanMethods = false)
public class AssetOperateLogConfiguration {

    @Bean
    public EntityNameParseFunction assetDataAssetNameParseFunction(DataAssetMapper dataAssetMapper) {
        return new EntityNameParseFunction("getDataAssetName",
                loader(dataAssetMapper::selectBatchIds, DataAssetDO::getId, row -> row.getAssetName()));
    }

    @Bean
    public EntityNameParseFunction assetAssetCategoryNameParseFunction(CategoryDataAssetMapper categoryDataAssetMapper) {
        return new EntityNameParseFunction("getAssetCategoryName",
                loader(categoryDataAssetMapper::selectBatchIds, CategoryDataAssetDO::getId, row -> row.getCategoryName()));
    }

    @Bean
    public EntityNameParseFunction assetAssetTagCategoryNameParseFunction(CategoryTagMapper categoryTagMapper) {
        return new EntityNameParseFunction("getAssetTagCategoryName",
                loader(categoryTagMapper::selectBatchIds, CategoryTagDO::getId, row -> row.getCategoryName()));
    }

    @Bean
    public EntityNameParseFunction assetAssetTagNameParseFunction(AssetTagMapper assetTagMapper) {
        return new EntityNameParseFunction("getAssetTagName",
                loader(assetTagMapper::selectBatchIds, AssetTagDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction assetAssetTagRelNameParseFunction(AssetTagRelMapper assetTagRelMapper, AssetTagMapper assetTagMapper) {
        return new EntityNameParseFunction("getAssetTagRelName",
                via(assetTagRelMapper::selectBatchIds, AssetTagRelDO::getId, AssetTagRelDO::getTagId,
                loader(assetTagMapper::selectBatchIds, AssetTagDO::getId, row -> row.getName())));
    }

    @Bean
    public EntityNameParseFunction assetAssetQualityTaskNameParseFunction(AssetQualityTaskMapper assetQualityTaskMapper) {
        return new EntityNameParseFunction("getAssetQualityTaskName",
                loader(assetQualityTaskMapper::selectBatchIds, AssetQualityTaskDO::getId, row -> row.getTaskName()));
    }

    @Bean
    public EntityNameParseFunction assetAssetCatalogVersionNameParseFunction(AssetCatalogVersionMapper assetCatalogVersionMapper, DataAssetMapper dataAssetMapper) {
        return new EntityNameParseFunction("getAssetCatalogVersionName",
                via(assetCatalogVersionMapper::selectBatchIds, AssetCatalogVersionDO::getId, AssetCatalogVersionDO::getAssetId,
                loader(dataAssetMapper::selectBatchIds, DataAssetDO::getId, row -> row.getAssetName())));
    }

    @Bean
    public EntityNameParseFunction assetAssetReviewTicketNameParseFunction(AssetReviewTicketMapper assetReviewTicketMapper, DataAssetMapper dataAssetMapper) {
        return new EntityNameParseFunction("getAssetReviewTicketName",
                via(assetReviewTicketMapper::selectBatchIds, AssetReviewTicketDO::getId, AssetReviewTicketDO::getAssetId,
                loader(dataAssetMapper::selectBatchIds, DataAssetDO::getId, row -> row.getAssetName())));
    }

    @Bean
    public EntityNameParseFunction assetAssetSensitiveNameParseFunction(AssetSensitiveListMapper assetSensitiveListMapper) {
        return new EntityNameParseFunction("getAssetSensitiveName",
                loader(assetSensitiveListMapper::selectBatchIds, AssetSensitiveListDO::getId, row -> row.getColumnName()));
    }

    @Bean
    public EntityNameParseFunction assetDataExportNameParseFunction(DataExportRequestMapper dataExportRequestMapper) {
        return new EntityNameParseFunction("getDataExportName",
                loader(dataExportRequestMapper::selectBatchIds, DataExportRequestDO::getId, row -> row.getTableName()));
    }

    @Bean
    public EntityNameParseFunction assetMarketCategoryNameParseFunction(MarketCategoryMapper marketCategoryMapper) {
        return new EntityNameParseFunction("getMarketCategoryName",
                loader(marketCategoryMapper::selectBatchIds, MarketCategoryDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction assetMarketDemandNameParseFunction(MarketDemandMapper marketDemandMapper) {
        return new EntityNameParseFunction("getMarketDemandName",
                loader(marketDemandMapper::selectBatchIds, MarketDemandDO::getId, row -> row.getTitle()));
    }

    @Bean
    public EntityNameParseFunction assetMarketDemandMatchNameParseFunction(MarketDemandMatchMapper marketDemandMatchMapper, MarketDemandMapper marketDemandMapper) {
        return new EntityNameParseFunction("getMarketDemandMatchName",
                via(marketDemandMatchMapper::selectBatchIds, MarketDemandMatchDO::getId, MarketDemandMatchDO::getDemandId,
                loader(marketDemandMapper::selectBatchIds, MarketDemandDO::getId, row -> row.getTitle())));
    }

    @Bean
    public EntityNameParseFunction assetMarketApplyNameParseFunction(MarketResourceApplyMapper marketResourceApplyMapper) {
        return new EntityNameParseFunction("getMarketApplyName",
                loader(marketResourceApplyMapper::selectBatchIds, MarketResourceApplyDO::getId, row -> row.getTitle()));
    }

    @Bean
    public EntityNameParseFunction assetMarketCommentNameParseFunction(MarketResourceCommentMapper marketResourceCommentMapper, DataAssetMapper dataAssetMapper) {
        return new EntityNameParseFunction("getMarketCommentName",
                via(marketResourceCommentMapper::selectBatchIds, MarketResourceCommentDO::getId, MarketResourceCommentDO::getAssetId,
                loader(dataAssetMapper::selectBatchIds, DataAssetDO::getId, row -> row.getAssetName())));
    }

    @Bean
    public EntityNameParseFunction assetMarketDeliveryOperationNameParseFunction(MarketDeliveryOperationMapper marketDeliveryOperationMapper, MarketResourceApplyMapper marketResourceApplyMapper) {
        return new EntityNameParseFunction("getMarketDeliveryOperationName",
                via(marketDeliveryOperationMapper::selectBatchIds, MarketDeliveryOperationDO::getId, MarketDeliveryOperationDO::getApplyId,
                loader(marketResourceApplyMapper::selectBatchIds, MarketResourceApplyDO::getId, row -> row.getTitle())));
    }

    // ========== 跨模块：治理（标准、术语、元数据）、数据服务（应用、API） ==========

    @Bean
    public EntityNameParseFunction assetStandardElementNameParseFunction(StandardElementApi standardElementApi) {
        return new EntityNameParseFunction("getAssetStandardElementName",
                loader(ids -> standardElementApi.getElementListByIds(ids).getCheckedData(),
                        StandardElementRespDTO::getId, StandardElementRespDTO::getName));
    }

    @Bean
    public EntityNameParseFunction assetStandardTermNameParseFunction(StandardTermApi standardTermApi) {
        return new EntityNameParseFunction("getAssetStandardTermName",
                loader(ids -> standardTermApi.getTermList(new ArrayList<>(ids)).getCheckedData(),
                        StandardTermRespDTO::getId, StandardTermRespDTO::getName));
    }

    @Bean
    public EntityNameParseFunction assetMetaTableNameParseFunction(MetadataApi metadataApi) {
        return new EntityNameParseFunction("getAssetMetaTableName", eachId(id -> {
            MetadataTableRespDTO table = metadataApi.getTable(id).getCheckedData();
            return table == null ? null : StrUtil.blankToDefault(table.getCustomComment(), table.getTableName());
        }));
    }

    @Bean
    public EntityNameParseFunction assetMetaColumnNameParseFunction(MetadataApi metadataApi) {
        return new EntityNameParseFunction("getAssetMetaColumnName", eachId(id -> {
            MetadataColumnRespDTO column = metadataApi.getColumn(id).getCheckedData();
            return column == null ? null : StrUtil.blankToDefault(column.getBusinessName(), column.getColumnName());
        }));
    }

    @Bean
    public EntityNameParseFunction assetSupplyApiNameParseFunction(SupplyDataApiApi supplyDataApiApi) {
        return new EntityNameParseFunction("getAssetSupplyApiName", eachId(id -> {
            SupplyApiBindingRespDTO api = supplyDataApiApi.getApiBinding(id).getCheckedData();
            return api == null ? null : api.getName();
        }));
    }

    @Bean
    public EntityNameParseFunction assetSupplyAppNameParseFunction(SupplyAppApi supplyAppApi) {
        return new EntityNameParseFunction("getAssetSupplyAppName",
                ids -> supplyAppApi.getAppNames(ids).getCheckedData());
    }

    /** 只提供单个查询的接口：逐个读取（日志里的引用通常只有一两个） */
    private static Function<Collection<Long>, Map<Long, String>> eachId(Function<Long, String> single) {
        return ids -> {
            Map<Long, String> names = new HashMap<>();
            ids.forEach(id -> names.put(id, single.apply(id)));
            return names;
        };
    }

    // ========== 业务代码翻译（类型、模式、级别等），未收录的值原样输出 ==========

    @Bean
    public CodeLabelParseFunction assetAssetScheduleTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getAssetScheduleTypeLabel", Map.ofEntries(Map.entry("0", "手动执行"), Map.entry("1", "定时调度")));
    }

    @Bean
    public CodeLabelParseFunction assetAssetIssueTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getAssetIssueTypeLabel", Map.ofEntries(Map.entry("1", "错误"), Map.entry("2", "缺失"), Map.entry("3", "口径不清"), Map.entry("4", "更新不及时"), Map.entry("5", "服务不可用")));
    }

    @Bean
    public CodeLabelParseFunction assetAssetTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getAssetTypeLabel", Map.ofEntries(Map.entry("1", "数据库表"), Map.entry("2", "API"), Map.entry("3", "文件")));
    }

    @Bean
    public CodeLabelParseFunction assetAssetShareAttributeLabelParseFunction() {
        return new CodeLabelParseFunction("getAssetShareAttributeLabel", Map.ofEntries(Map.entry("0", "待核验"), Map.entry("1", "无条件共享"), Map.entry("2", "有条件共享"), Map.entry("3", "不予共享")));
    }

    @Bean
    public CodeLabelParseFunction assetMarketDemandTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getMarketDemandTypeLabel", Map.ofEntries(Map.entry("1", "新增数据"), Map.entry("2", "已有共享"), Map.entry("3", "质量提升"), Map.entry("4", "其他")));
    }

    @Bean
    public CodeLabelParseFunction assetMarketReplyTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getMarketReplyTypeLabel", Map.ofEntries(Map.entry("1", "沟通"), Map.entry("2", "可供给"), Map.entry("3", "无法供给")));
    }

    @Bean
    public CodeLabelParseFunction assetMarketDeliveryActionLabelParseFunction() {
        return new CodeLabelParseFunction("getMarketDeliveryActionLabel", Map.ofEntries(Map.entry("REVOKE", "撤销授权"), Map.entry("CONFIRM_ORIGINAL", "确认原请求")));
    }

}
