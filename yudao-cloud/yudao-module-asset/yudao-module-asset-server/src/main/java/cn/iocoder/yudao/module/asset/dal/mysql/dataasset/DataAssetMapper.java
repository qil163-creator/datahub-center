package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetMapPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface DataAssetMapper extends BaseMapperX<DataAssetDO> {

    /**
     * 按指定条件读取并锁定数据资产记录，供事务内一致性校验或修改使用。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_asset_data_asset WHERE id=#{id} AND deleted=0 FOR UPDATE")
    DataAssetDO selectForCatalogUpdate(@Param("id") Long id);

    /** 市场过滤条件与返回值都来自同一发布快照，不能用草稿命中搜索。 */
    default PageResult<DataAssetDO> selectPublishedPage(DataAssetPageReqVO req, Collection<Long> categoryIds,
            Collection<Long> assetIds) {
        return selectPage(req, publishedQuery(req, categoryIds, assetIds)
                .orderByDesc(DataAssetDO::getPublishTime).orderByDesc(DataAssetDO::getId));
    }

    /**
     * 构造已发布资产的筛选条件。
     */
    private LambdaQueryWrapperX<DataAssetDO> publishedQuery(DataAssetPageReqVO req, Collection<Long> categoryIds,
            Collection<Long> assetIds) {
        var query = new LambdaQueryWrapperX<DataAssetDO>()
                .eq(DataAssetDO::getStatus, DataAssetDO.STATUS_PUBLISHED)
                .inIfPresent(DataAssetDO::getId, assetIds);
        String snapshot = "(SELECT COALESCE(v.content_json::jsonb->'asset',v.content_json::jsonb) FROM dh_asset_catalog_version v"
                + " WHERE v.id=published_catalog_version_id AND v.asset_id=dh_asset_data_asset.id"
                + " AND v.deleted=0)";
        if (req.getTagId() != null) {
            query.apply(
                    "EXISTS (SELECT 1 FROM dh_asset_catalog_version tv, jsonb_array_elements(COALESCE(tv.content_json::jsonb->'tags','[]'::jsonb)) tag"
                            + " WHERE tv.id=published_catalog_version_id AND tv.asset_id=dh_asset_data_asset.id"
                            + " AND tv.deleted=0 AND tag->>'id'={0})", String.valueOf(req.getTagId()));
        }
        if (categoryIds != null && !categoryIds.isEmpty()) {
            query.and(group -> categoryIds.forEach(
                    id -> group.or().apply(snapshot + "->>'categoryId'={0}", String.valueOf(id))));
        }
        if (req.getAssetType() != null) {
            query.apply(snapshot + "->>'assetType'={0}", String.valueOf(req.getAssetType()));
        }
        if (req.getDatasourceId() != null) {
            query.apply(snapshot + "->>'datasourceId'={0}", String.valueOf(req.getDatasourceId()));
        }
        if (req.getSubjectDomainId() != null) {
            query.apply(snapshot + "->>'subjectDomainId'={0}", String.valueOf(req.getSubjectDomainId()));
        }
        if (StrUtil.isNotBlank(req.getLayer())) {
            query.apply(snapshot + "->>'layer'={0}", req.getLayer());
        }
        if (StrUtil.isNotBlank(req.getKeyword())) {
            query.apply("(" + snapshot + "->>'assetName' ILIKE {0} OR " + snapshot
                            + "->>'tableName' ILIKE {0} OR " + snapshot + "->>'description' ILIKE {0})",
                    "%" + req.getKeyword() + "%");
        }
        return query;
    }

    /** 地图仅按当前登录用户及部门的维护关系过滤，条件在数据库分页前生效。 */
    default PageResult<DataAssetDO> selectMapPage(DataAssetPageReqVO req, Collection<Long> categoryIds,
            Collection<Long> taggedAssetIds, Long userId, Long deptId) {
        var query = assetQuery(req, categoryIds, taggedAssetIds);
        applyMaintenanceScope(query, userId, deptId);
        return selectPage(req, query);
    }

    /**
     * 应用维护范围。
     */
    private void applyMaintenanceScope(LambdaQueryWrapper<DataAssetDO> query, Long userId, Long deptId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("地图查询必须有登录身份");
        }
        query.and(owner -> {
            owner.eq(DataAssetDO::getTechLeaderUserId, userId)
                    .or().eq(DataAssetDO::getBusinessLeaderUserId, userId)
                    .or().eq(DataAssetDO::getCreator, String.valueOf(userId));
            if (deptId != null) {
                owner.or().eq(DataAssetDO::getOwnerDeptId, deptId)
                        .or().eq(DataAssetDO::getProviderDeptId, deptId);
            }
        });
    }

    /** 分组与分页复用同一用户、部门条件，直接聚合当前资产字段。 */
    default List<Map<String, Object>> selectMapFacetCounts(Long userId, Long deptId) {
        var query = new LambdaQueryWrapperX<DataAssetDO>().apply("deleted = 0");
        applyMaintenanceScope(query, userId, deptId);
        return selectMapFacetCounts(query);
    }

    /**
     * 按指定条件统计数据资产的查询映射分面数量记录数。
     */
    @Select("""
            WITH scoped AS (
              SELECT * FROM dh_asset_data_asset ${ew.customSqlSegment}
            )
            SELECT 'total' kind, NULL group_key, NULL group_label, COUNT(*) cnt FROM scoped
            UNION ALL
            SELECT 'category', category_id::text, NULL, COUNT(*) FROM scoped
              WHERE category_id IS NOT NULL GROUP BY category_id
            UNION ALL
            SELECT 'subject', subject_domain_id::text, MAX(subject_domain_name), COUNT(*) FROM scoped
              WHERE subject_domain_id IS NOT NULL GROUP BY subject_domain_id
            UNION ALL
            SELECT 'layer', layer, layer, COUNT(*) FROM scoped
              WHERE layer IS NOT NULL AND layer <> '' GROUP BY layer
            """)
    List<Map<String, Object>> selectMapFacetCounts(@Param(Constants.WRAPPER) LambdaQueryWrapper<DataAssetDO> wrapper);

    /** 市场筛选在数据库分页之前应用，不能先截一页再按当前用户收藏或授权过滤。 */
    default PageResult<DataAssetDO> selectMarketPage(MarketPageReqVO req, Long userId,
            Collection<Long> authorizedIds, Collection<Long> marketCategoryIds) {
        if (Boolean.TRUE.equals(req.getAuthorizedOnly()) && (authorizedIds == null || authorizedIds.isEmpty())) {
            return PageResult.empty();
        }
        var source = new DataAssetPageReqVO();
        var query = publishedQuery(source, null, Boolean.TRUE.equals(req.getAuthorizedOnly()) ? authorizedIds : null);
        String snapshot = "(SELECT COALESCE(v.content_json::jsonb->'asset',v.content_json::jsonb) FROM dh_asset_catalog_version v"
                + " WHERE v.id=published_catalog_version_id AND v.asset_id=dh_asset_data_asset.id AND v.deleted=0)";
        if (StrUtil.isNotBlank(req.getKeyword())) {
            query.apply("(" + snapshot + "->>'assetName' ILIKE {0} OR " + snapshot + "->>'tableName' ILIKE {0} OR "
                    + snapshot + "->>'description' ILIKE {0} OR EXISTS(SELECT 1 FROM dh_asset_market_listing ml"
                    + " WHERE ml.asset_id=dh_asset_data_asset.id AND ml.deleted=0"
                    + " AND (ml.display_name ILIKE {0} OR ml.description ILIKE {0})))", "%" + req.getKeyword() + "%");
        }
        if (marketCategoryIds != null && !marketCategoryIds.isEmpty()) {
            query.and(group -> marketCategoryIds.forEach(id -> group.or().apply(
                    "EXISTS(SELECT 1 FROM dh_asset_market_listing ml WHERE ml.asset_id=dh_asset_data_asset.id"
                            + " AND ml.deleted=0 AND ml.market_category_id={0})", id)));
        }
        if (req.getAssetTypes() != null && !req.getAssetTypes().isEmpty()) {
            query.and(group -> req.getAssetTypes().forEach(type ->
                    group.or().apply("(EXISTS(SELECT 1 FROM dh_asset_market_listing ml"
                            + " WHERE ml.asset_id=dh_asset_data_asset.id AND ml.deleted=0"
                            + " AND {0}=ANY(string_to_array(ml.delivery_types,',')))"
                            + " OR (NOT EXISTS(SELECT 1 FROM dh_asset_market_listing ml"
                            + " WHERE ml.asset_id=dh_asset_data_asset.id AND ml.deleted=0) AND "
                            + snapshot + "->>'assetType'={0}))", String.valueOf(type))));
        }
        if (Boolean.TRUE.equals(req.getFavoriteOnly())) {
            query.apply("EXISTS (SELECT 1 FROM dh_asset_market_favorite f WHERE f.asset_id=dh_asset_data_asset.id"
                    + " AND f.user_id={0} AND f.deleted=0)", userId);
        }
        if ("FAVORITE".equals(req.getSort())) {
            return selectPage(req, query.last("ORDER BY (SELECT COUNT(*) FROM dh_asset_market_favorite hot"
                    + " WHERE hot.asset_id=dh_asset_data_asset.id AND hot.deleted=0) DESC, publish_time DESC, id DESC"));
        }
        if ("POPULAR".equals(req.getSort())) {
            query.orderByDesc(DataAssetDO::getViewCount);
        }
        return selectPage(req, query.orderByDesc(DataAssetDO::getPublishTime).orderByDesc(DataAssetDO::getId));
    }

    /**
     * 按指定条件统计数据资产的查询已发布分面数量记录数。
     */
    @Select("""
            WITH published AS (
              SELECT COALESCE(v.content_json::jsonb->'asset',v.content_json::jsonb,'{}'::jsonb) content
              FROM dh_asset_data_asset a LEFT JOIN dh_asset_catalog_version v
                ON v.id=a.published_catalog_version_id AND v.asset_id=a.id AND v.deleted=0
              WHERE a.status=1 AND a.deleted=0
            )
            SELECT 'category' kind,content->>'categoryId' group_key,NULL group_label,COUNT(*) cnt
              FROM published WHERE content->>'categoryId' IS NOT NULL GROUP BY content->>'categoryId'
            UNION ALL
            SELECT 'subject' kind,content->>'subjectDomainId' group_key,MAX(content->>'subjectDomainName') group_label,COUNT(*) cnt
              FROM published WHERE content->>'subjectDomainId' IS NOT NULL GROUP BY content->>'subjectDomainId'
            UNION ALL
            SELECT 'layer' kind,content->>'layer' group_key,content->>'layer' group_label,COUNT(*) cnt
              FROM published WHERE content->>'layer' IS NOT NULL GROUP BY content->>'layer'
            """)
    List<Map<String, Object>> selectPublishedFacetCounts();

    /** 匹配及提供方决定的末次复验：共享锁保护资产到事务提交，不串行化同一资产上的其他匹配或决定。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT * FROM dh_asset_data_asset
            WHERE id=#{id} AND deleted=0
            FOR SHARE
            """)
    DataAssetDO selectForMatching(@Param("id") long id);

    /**
     * 查询数据资产分页。
     */
    default PageResult<DataAssetDO> selectPage(DataAssetPageReqVO reqVO,
            Collection<Long> categoryIds,
            Collection<Long> assetIds) {
        return selectPage(reqVO, assetQuery(reqVO, categoryIds, assetIds));
    }

    /**
     * 构造资产管理列表的组合筛选条件。
     */
    private LambdaQueryWrapperX<DataAssetDO> assetQuery(DataAssetPageReqVO reqVO,
            Collection<Long> categoryIds, Collection<Long> assetIds) {
        LambdaQueryWrapperX<DataAssetDO> wrapper = new LambdaQueryWrapperX<DataAssetDO>()
                .inIfPresent(DataAssetDO::getCategoryId, categoryIds)
                .inIfPresent(DataAssetDO::getId, assetIds)
                .eqIfPresent(DataAssetDO::getAssetType, reqVO.getAssetType())
                .eqIfPresent(DataAssetDO::getStatus, reqVO.getStatus())
                .eqIfPresent(DataAssetDO::getDatasourceId, reqVO.getDatasourceId())
                .eqIfPresent(DataAssetDO::getLayer, reqVO.getLayer())
                .eqIfPresent(DataAssetDO::getSubjectDomainId, reqVO.getSubjectDomainId());
        if (reqVO instanceof DataAssetMapPageReqVO mapRequest && mapRequest.getDataDomainId() != null) {
            wrapper.apply("CASE WHEN pg_input_is_valid(registration_config_json, 'jsonb') "
                            + "THEN registration_config_json::jsonb ->> 'dataDomainId' END = {0}",
                    String.valueOf(mapRequest.getDataDomainId()));
        }
        if (StrUtil.isNotBlank(reqVO.getKeyword())) {
            wrapper.and(query -> query.like(DataAssetDO::getAssetName, reqVO.getKeyword())
                    .or().like(DataAssetDO::getTableName, reqVO.getKeyword())
                    .or().like(DataAssetDO::getDescription, reqVO.getKeyword()));
        }
        wrapper.orderByDesc(DataAssetDO::getStatus)
                .orderByDesc(DataAssetDO::getCreateTime)
                .orderByDesc(DataAssetDO::getId);
        return wrapper;
    }

    /**
     * 按元数据表编号查询数据资产。
     */
    default DataAssetDO selectByMetaTableId(Long metaTableId) {
        return selectOne(DataAssetDO::getMetaTableId, metaTableId);
    }

    /**
     * 按负责人用户编号查询编号。
     */
    default List<Long> selectIdsByLeaderUserId(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<DataAssetDO>()
                .and(wrapper -> wrapper.eq(DataAssetDO::getTechLeaderUserId, userId)
                        .or().eq(DataAssetDO::getBusinessLeaderUserId, userId)))
                .stream().map(DataAssetDO::getId).toList();
    }

    /**
     * 按所属人部门编号查询编号。
     */
    default List<Long> selectIdsByOwnerDeptId(Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<DataAssetDO>()
                .eq(DataAssetDO::getOwnerDeptId, deptId))
                .stream().map(DataAssetDO::getId).toList();
    }

    /**
     * 按数据源编号查询表。
     */
    default List<DataAssetDO> selectTablesByDatasourceId(Long datasourceId) {
        return selectList(new LambdaQueryWrapperX<DataAssetDO>()
                .eq(DataAssetDO::getDatasourceId, datasourceId)
                .eq(DataAssetDO::getAssetType, DataAssetDO.TYPE_DATABASE_TABLE));
    }

    /** 入参为物理名称。缺 schema 的历史记录也返回，由业务层拒绝不完整绑定而非漏过授权。 */
    default List<DataAssetDO> selectExactTableBindings(Long datasourceId, String schemaName, String tableName) {
        return selectTablesByDatasourceId(datasourceId).stream()
                .filter(asset -> java.util.Objects.equals(asset.getTableName(), tableName))
                .filter(asset -> StrUtil.isBlank(asset.getSchemaName())
                        || java.util.Objects.equals(asset.getSchemaName(), schemaName))
                .toList();
    }

    /**
     * 按分类编号查询数据资产数量。
     */
    default Long selectCountByCategoryId(Long categoryId) {
        return selectCount(DataAssetDO::getCategoryId, categoryId);
    }

    /**
     * 按类目分组的资产数：每行 {categoryId, cnt}
     */
    default List<Map<String, Object>> selectCountGroupByCategory() {
        return selectMaps(Wrappers.<DataAssetDO>query()
                .select("category_id AS group_key", "COUNT(*) AS cnt")
                .isNotNull("category_id")
                .groupBy("category_id"));
    }

    /**
     * 按主题域分组的资产数：每行 {subjectDomainId, subjectDomainName, cnt}
     */
    default List<Map<String, Object>> selectCountGroupBySubjectDomain() {
        return selectMaps(Wrappers.<DataAssetDO>query()
                .select("subject_domain_id AS group_key", "MAX(subject_domain_name) AS group_label",
                        "COUNT(*) AS cnt")
                .isNotNull("subject_domain_id")
                .groupBy("subject_domain_id"));
    }

    /**
     * 按数仓分层分组的资产数：每行 {layer, cnt}
     */
    default List<Map<String, Object>> selectCountGroupByLayer() {
        return selectMaps(Wrappers.<DataAssetDO>query()
                .select("layer AS group_key", "COUNT(*) AS cnt")
                .isNotNull("layer")
                .ne("layer", "")
                .groupBy("layer"));
    }


    /**
     * 按外部资产编码读取（迁入资产，如 qdata:market:{市场编号}）；不存在时返回 null。
     */
    default DataAssetDO selectByExternalCode(String externalCode) {
        if (externalCode == null || externalCode.isBlank()) {
            return null;
        }
        return selectOne(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<DataAssetDO>()
                .eq(DataAssetDO::getExternalCode, externalCode).last("LIMIT 1"));
    }

}
