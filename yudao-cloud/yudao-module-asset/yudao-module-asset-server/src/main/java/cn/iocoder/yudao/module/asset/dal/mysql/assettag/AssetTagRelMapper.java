package cn.iocoder.yudao.module.asset.dal.mysql.assettag;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.AssetTagRelDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagLinkedPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagLinkedRespVO;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mapper
public interface AssetTagRelMapper extends BaseMapperX<AssetTagRelDO> {

    String SCOPE = """
            <if test='!all'> AND (a.tech_leader_user_id=#{userId} OR a.business_leader_user_id=#{userId}
              OR a.creator=#{actor}<if test='deptId != null'> OR a.owner_dept_id=#{deptId} OR a.provider_dept_id=#{deptId}</if>)</if>
            """;

    /** 仅接受来源快照的明确日期事实，无效内容降级到本地创建时间。 */
    String SOURCE_CREATE_TIME = """
            CASE WHEN jsonb_typeof(d.file_info_json->'sourceCreateTime')='string'
              AND d.file_info_json->>'sourceCreateTime' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?$'
              AND pg_input_is_valid(d.file_info_json->>'sourceCreateTime','timestamp without time zone')
              THEN (d.file_info_json->>'sourceCreateTime')::timestamp END
            """;

    /**
     * 按指定条件读取并锁定资产标签关联记录，供事务内一致性校验或修改使用。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_asset_tag_rel WHERE id=#{id} AND deleted=0 FOR UPDATE")
    AssetTagRelDO selectForUpdate(@Param("id") Long id);

    /**
     * 按指定条件统计资产标签关联的指定范围数量记录数。
     */
    @Select("<script>SELECT r.tag_id,COUNT(*) cnt FROM dh_asset_tag_rel r JOIN dh_asset_data_asset a ON a.id=r.asset_id AND a.deleted=0 WHERE r.deleted=0 AND r.tag_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + SCOPE + " GROUP BY r.tag_id</script>")
    List<Map<String, Object>> scopedCounts(@Param("ids") Collection<Long> ids, @Param("userId") Long userId,
            @Param("deptId") Long deptId, @Param("actor") String actor, @Param("all") boolean all);

    /**
     * 分页查询与指定标签关联的资产，应用可见范围条件。
     */
    @Select("""
            <script>SELECT r.id relation_id,r.revision relation_revision,a.id asset_id,d.source_asset_id source_record_id,a.asset_name,a.table_name,
              a.description,a.asset_type,c.category_name,a.subject_domain_name,a.creator,a.create_time,
              CASE WHEN jsonb_typeof(d.file_info_json->'sourceCreatorName')='string'
                THEN NULLIF(btrim(d.file_info_json->>'sourceCreatorName'),'') END source_creator_name,
            """ + SOURCE_CREATE_TIME + """
              source_create_time
            FROM dh_asset_tag_rel r JOIN dh_asset_data_asset a ON a.id=r.asset_id AND a.deleted=0
              LEFT JOIN dh_asset_import_dataset d ON d.asset_id=a.id AND d.deleted=0
              LEFT JOIN dh_asset_category_data_asset c ON c.id=a.category_id AND c.deleted=0
            WHERE r.deleted=0 AND r.tag_id=#{req.id}
            """ + SCOPE + """
            <if test='req.keyword != null and req.keyword != ""'> AND (a.asset_name ILIKE CONCAT('%',#{req.keyword},'%')
               OR a.table_name ILIKE CONCAT('%',#{req.keyword},'%'))</if>
            ORDER BY <choose><when test='req.sortField == "assetId"'>
              CASE WHEN d.source_asset_id ~ '^[0-9]+$' THEN d.source_asset_id::numeric ELSE a.id::numeric END
              </when><otherwise>COALESCE(
            """ + SOURCE_CREATE_TIME + """
              ,a.create_time)</otherwise></choose>
              <choose><when test='req.sortOrder == "asc"'>ASC</when><otherwise>DESC</otherwise></choose>,
              a.id <choose><when test='req.sortOrder == "asc"'>ASC</when><otherwise>DESC</otherwise></choose>,r.id DESC</script>
            """)
    IPage<AssetTagLinkedRespVO> linkedPage(IPage<AssetTagLinkedRespVO> page, @Param("req") AssetTagLinkedPageReqVO req,
            @Param("userId") Long userId, @Param("deptId") Long deptId, @Param("actor") String actor,
            @Param("all") boolean all);

    /**
     * 按资产编号查询资产标签关联列表。
     */
    default List<AssetTagRelDO> selectListByAssetIds(Collection<Long> assetIds) {
        return selectList(new LambdaQueryWrapperX<AssetTagRelDO>()
                .in(AssetTagRelDO::getAssetId, assetIds)
                .orderByAsc(AssetTagRelDO::getId));
    }

    /**
     * 按标签编号查询资产标签关联列表。
     */
    default List<AssetTagRelDO> selectListByTagId(Long tagId) {
        return selectList(AssetTagRelDO::getTagId, tagId);
    }

    /**
     * 按标签编号查询资产标签关联数量。
     */
    default Long selectCountByTagId(Long tagId) {
        return selectCount(AssetTagRelDO::getTagId, tagId);
    }

    /**
     * 各标签已绑定的资产数量：tagId -> count
     */
    default Map<Long, Long> selectCountGroupByTagId(Collection<Long> tagIds) {
        Map<Long, Long> result = new HashMap<>();
        if (tagIds == null || tagIds.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> rows = selectMaps(Wrappers.<AssetTagRelDO>query()
                .select("tag_id AS tagId", "COUNT(*) AS cnt")
                .in("tag_id", tagIds)
                .groupBy("tag_id"));
        for (Map<String, Object> row : rows) {
            Object key = row.getOrDefault("tagId", row.get("tagid"));
            Object value = row.get("cnt");
            if (key instanceof Number keyNumber && value instanceof Number valueNumber) {
                result.put(keyNumber.longValue(), valueNumber.longValue());
            }
        }
        return result;
    }

    /**
     * 按资产编号删除资产标签关联记录。
     */
    default void deleteByAssetId(Long assetId) {
        delete(AssetTagRelDO::getAssetId, assetId);
    }

}
