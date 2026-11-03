package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDepartmentAssetCategoryRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDepartmentAssetPageReqVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** 申请读取按服务端实时部门范围过滤；来源申请只复用明确的本地部门映射，不猜测用户身份。 */
@Mapper
public interface MarketDepartmentAssetMapper {

    String FROM = """
            FROM dh_market_resource_apply r
            LEFT JOIN dh_asset_catalog_version v ON v.id=r.catalog_version_id
              AND v.asset_id=r.asset_id AND v.deleted=0 AND v.status=4
            LEFT JOIN dh_asset_data_asset a ON a.id=r.asset_id AND a.deleted=0
            LEFT JOIN dh_asset_market_listing l ON l.asset_id=r.asset_id AND l.deleted=0
            LEFT JOIN dh_asset_market_category c ON c.id=l.market_category_id AND c.deleted=0
            LEFT JOIN dh_market_approval_work_order w ON w.id=r.source_work_order_id AND w.deleted=0
            """;
    String SCOPE = """
            WHERE r.deleted=0 AND (
              (r.source_work_order_id IS NULL AND
                <choose>
                  <when test="userIds != null and !userIds.isEmpty()">
                    r.apply_user_id IN
                    <foreach collection="userIds" item="userId" open="(" close=")" separator=",">#{userId}</foreach>
                  </when>
                  <otherwise>FALSE</otherwise>
                </choose>
              ) OR (r.source_work_order_id IS NOT NULL AND w.id IS NOT NULL AND r.apply_dept_id=#{deptId})
            )
            """;
    String NAME = "COALESCE(NULLIF(w.mart_name,''),NULLIF(l.display_name,'')," + MarketMyAssetMapper.NAME + ")";
    String TYPE = "CASE WHEN r.api_id IS NOT NULL THEN 2 ELSE r.asset_type END";
    String COLUMNS = "SELECT r.*," + MarketMyAssetMapper.NAME + " asset_name," + NAME + " market_name,"
            + MarketMyAssetMapper.PINNED_ASSET + "->>'assetCode' asset_code,"
            + "l.market_category_id,c.name market_category_name,l.frequency update_cycle,"
            + "l.delivery_types declared_delivery_types," + TYPE + " delivery_type,"
            + "w.source_record_id source_apply_id,w.source_system,w.mart_source_id source_market_id,"
            + "w.detail_snapshot::text source_detail_snapshot,a.external_code source_asset_external_code ";
    String FILTER = """
            <if test="req.keyword != null and req.keyword.trim() != ''">
              AND
            """ + NAME + " ILIKE '%'||#{req.keyword}||'%' </if>"
            + """
            <if test="req.deliveryType != null">
              AND CASE WHEN r.source_work_order_id IS NOT NULL
                THEN w.detail_snapshot->'resource'->'deliveryTypes' @&gt; jsonb_build_array(#{req.deliveryType}::integer)
                WHEN NULLIF(l.delivery_types,'') IS NOT NULL
                THEN #{req.deliveryType}::text=ANY(string_to_array(l.delivery_types,','))
                ELSE
            """ + TYPE + "=#{req.deliveryType} END </if>"
            + """
            <if test="categoryIds != null">
              AND l.market_category_id IN
              <foreach collection="categoryIds" item="categoryId" open="(" close=")" separator=",">#{categoryId}</foreach>
            </if>
            """;
    String ORDER = "<choose><when test=\"req.sortField == 'applyId'\">"
            + "CASE WHEN w.source_record_id ~ '^[0-9]+$' THEN w.source_record_id::numeric ELSE r.id::numeric END"
            + "</when><otherwise>r.create_time</otherwise></choose>"
            + "<choose><when test=\"req.sortOrder == 'asc'\"> ASC </when><otherwise> DESC </otherwise></choose>"
            + " NULLS LAST,r.id";

    /**
     * 按指定条件统计部门资产申请的记录数。
     */
    @Select("<script>SELECT COUNT(*) " + FROM + SCOPE + FILTER + "</script>")
    long count(@Param("req") MarketDepartmentAssetPageReqVO req, @Param("deptId") Long deptId,
            @Param("userIds") Collection<Long> userIds, @Param("categoryIds") Collection<Long> categoryIds);

    /**
     * 查询部门资产申请分页。
     */
    @Select("<script>" + COLUMNS + FROM + SCOPE + FILTER + " ORDER BY " + ORDER
            + " LIMIT #{req.pageSize} OFFSET #{offset}</script>")
    List<MarketDepartmentAssetRow> selectPage(@Param("req") MarketDepartmentAssetPageReqVO req,
            @Param("deptId") Long deptId, @Param("userIds") Collection<Long> userIds,
            @Param("categoryIds") Collection<Long> categoryIds, @Param("offset") long offset);

    /**
     * 查询范围内部门。
     */
    @Select("<script>" + COLUMNS + FROM + SCOPE + " AND r.id=#{id}</script>")
    MarketDepartmentAssetRow selectInDepartment(@Param("id") long id, @Param("deptId") Long deptId,
            @Param("userIds") Collection<Long> userIds);

    /**
     * 查询分类。
     */
    @Select("<script>WITH RECURSIVE visible AS (SELECT DISTINCT c.id,c.parent_id,c.name " + FROM + SCOPE
            + " AND c.id IS NOT NULL),tree AS (SELECT * FROM visible UNION "
            + "SELECT p.id,p.parent_id,p.name FROM dh_asset_market_category p JOIN tree t ON t.parent_id=p.id WHERE p.deleted=0) "
            + "SELECT id,parent_id,name FROM tree ORDER BY id</script>")
    List<MarketDepartmentAssetCategoryRespVO> selectCategories(@Param("deptId") Long deptId,
            @Param("userIds") Collection<Long> userIds);
}
