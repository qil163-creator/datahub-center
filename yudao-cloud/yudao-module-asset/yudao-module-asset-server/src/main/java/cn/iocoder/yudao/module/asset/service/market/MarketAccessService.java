package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 数据集市授权：审批通过且未收回/过期后，才允许预览和查询对应库表。
 */
public interface MarketAccessService {

    /**
     * 判断市场访问是否生效。
     */
    boolean isEffective(MarketResourceApplyDO apply);

    /**
     * 查找生效申请。
     */
    MarketResourceApplyDO findEffectiveApply(Long userId, Long assetId);

    /** 同一数据库时点批量复核已发布资产的有效入口及冻结绑定，null 表示当前用户全部已发布候选。 */
    java.util.Map<Long, MarketResourceApplyDO> findEffectiveApplies(Long userId, Collection<Long> assetIds);

    /**
     * 判断市场访问是否具有生效访问。
     */
    boolean hasEffectiveAccess(Long userId, Long assetId);

    /**
     * 查找生效申请用于表。
     */
    MarketResourceApplyDO findEffectiveApplyForTable(Long userId, Long datasourceId, String tableName);

    /** 物理 schema/table 已由 DataForge/DB Switch 解析，禁止再次去引号或折叠大小写。 */
    MarketResourceApplyDO findEffectiveApplyForTable(Long userId, Long datasourceId,
            String schemaName, String tableName);

    /**
     * 查询已授权表名称。
     */
    List<String> listAuthorizedTableNames(Long userId, Long datasourceId);

    /**
     * 预览已授权资产。
     */
    DatabasePreviewRespDTO previewAuthorizedAsset(Long assetId, Integer limit);

    /** 我的资产按具体批准来源进入预览；不能改选同资产另一条有效授权替代失效申请。 */
    DatabasePreviewRespDTO previewAuthorizedApply(Long applyId, Integer limit);

    /** 以有效申请定位资产，合并同一主体、资产、兼容批准条件的所有有效来源并保留逐来源凭证。 */
    MarketQuerySnapshot requireQuerySnapshot(Long userId, Long applyId);

    /**
     * 校验市场访问的仍然当前，不满足约束时拒绝当前操作。
     */
    void assertStillCurrent(MarketQuerySnapshot snapshot);

    /**
     * 解析市场访问的字段范围。
     */
    Set<String> parseFieldScope(String fieldScope);

    /**
     * 校验市场访问的查询字段允许，不满足约束时拒绝当前操作。
     */
    void assertQueryColumnsAllowed(MarketFieldScope scope, boolean wildcard, Collection<String> sourceColumns);

    /**
     * 筛选预览。
     */
    <T> T filterPreview(T preview, MarketFieldScope scope);

}
