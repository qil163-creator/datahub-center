package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetPageReqVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** 本人的批准来源固定在 SQL 中过滤，名称检索和展示均不读取当前资产草稿。 */
@Mapper
public interface MarketMyAssetMapper {

    String FROM = """
            FROM dh_market_resource_apply r
            LEFT JOIN dh_asset_catalog_version v ON v.id=r.catalog_version_id
              AND v.asset_id=r.asset_id AND v.deleted=0 AND v.status=4
            LEFT JOIN dh_asset_market_listing l ON l.asset_id=r.asset_id AND l.deleted=0
            LEFT JOIN dh_asset_market_category c ON c.id=l.market_category_id AND c.deleted=0
            """;
    String CATALOG_JSON = "(CASE WHEN v.content_json IS JSON OBJECT THEN v.content_json::jsonb ELSE '{}'::jsonb END)";
    String PINNED_ASSET = "(CASE WHEN " + CATALOG_JSON + "->>'format'='1'"
            + " AND " + CATALOG_JSON + "->'asset'->>'id'=r.asset_id::text"
            + " THEN " + CATALOG_JSON + "->'asset' ELSE NULL END)";
    String NAME = "COALESCE(NULLIF(" + PINNED_ASSET + "->>'assetName',''),"
            + "NULLIF(" + CATALOG_JSON + "->>'assetName',''),'历史目录 #'||r.asset_id::text)";
    String MARKET_NAME = "COALESCE(NULLIF(l.display_name,'')," + NAME + ")";
    String TYPE = "CASE WHEN r.api_id IS NOT NULL THEN 2 ELSE r.asset_type END";
    String COLUMNS = "SELECT r.*, " + NAME + " asset_name," + MARKET_NAME + " market_name,"
            + PINNED_ASSET + "->>'assetCode' asset_code,"
            + "l.market_category_id,c.name market_category_name,"
            + "COALESCE(NULLIF(l.frequency,'')," + PINNED_ASSET + "->>'updateCycle',"
            + CATALOG_JSON + "->>'updateRule') update_cycle,"
            + "COALESCE(" + PINNED_ASSET + "->>'description'," + CATALOG_JSON + "->>'description') description,"
            + "r.snapshot::jsonb->>'assetVersion' asset_version,"
            + "(" + PINNED_ASSET + " IS NOT NULL) frozen_catalog_valid,"
            + TYPE + " delivery_type ";
    String WHERE = """
            WHERE r.deleted=0 AND r.apply_user_id=#{userId} AND r.status IN (2,5,6)
            <if test="req.keyword != null and req.keyword.trim() != ''">
              AND (
            """ + MARKET_NAME + " ILIKE '%'||#{req.keyword}||'%' OR " + NAME + " ILIKE '%'||#{req.keyword}||'%')"
            + """
            </if>
            <if test="req.deliveryType != null"> AND (
            """ + TYPE + ")=#{req.deliveryType}</if>"
            + """
            <if test="categoryIds != null">
              AND l.market_category_id IN
              <foreach collection="categoryIds" item="categoryId" open="(" close=")" separator=",">#{categoryId}</foreach>
            </if>
            """;

    /**
     * 按指定条件统计本人获批资产的本人持有记录数。
     */
    @Select("<script>SELECT COUNT(*) " + FROM + WHERE + "</script>")
    long countOwned(@Param("userId") long userId, @Param("req") MarketMyAssetPageReqVO req,
            @Param("categoryIds") Collection<Long> categoryIds);

    /**
     * 查询本人持有分页。
     */
    @Select("<script>" + COLUMNS + FROM + WHERE
            + " ORDER BY r.create_time DESC,r.id DESC LIMIT #{req.pageSize} OFFSET #{offset}</script>")
    List<MarketMyAssetRow> selectOwnedPage(@Param("userId") long userId, @Param("req") MarketMyAssetPageReqVO req,
            @Param("categoryIds") Collection<Long> categoryIds, @Param("offset") long offset);

    /**
     * 查询本人持有。
     */
    @Select(COLUMNS + FROM + " WHERE r.deleted=0 AND r.apply_user_id=#{userId} AND r.id=#{id} AND r.status IN (2,5,6)")
    MarketMyAssetRow selectOwned(@Param("userId") long userId, @Param("id") long id);
}
