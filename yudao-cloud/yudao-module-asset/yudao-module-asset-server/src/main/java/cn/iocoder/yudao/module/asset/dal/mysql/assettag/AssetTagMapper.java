package cn.iocoder.yudao.module.asset.dal.mysql.assettag;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.assettag.AssetTagDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.*;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mapper
public interface AssetTagMapper extends BaseMapperX<AssetTagDO> {

    /**
     * 按指定条件读取并锁定资产标签记录，供事务内一致性校验或修改使用。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_asset_tag WHERE id=#{id} AND deleted=0 FOR UPDATE")
    AssetTagDO selectForUpdate(@Param("id") Long id);

    /**
     * 按指定条件统计资产标签的指定范围分页记录数。
     */
    @Select("""
            <script>SELECT t.* FROM dh_asset_tag t WHERE t.deleted=0
            <if test='req.name != null and req.name != ""'> AND (t.name ILIKE CONCAT('%',#{req.name},'%')
              OR t.near_synonyms ILIKE CONCAT('%',#{req.name},'%') OR t.synonyms ILIKE CONCAT('%',#{req.name},'%'))</if>
            <if test='req.status != null'> AND t.status=#{req.status}</if>
            <if test='req.creatorId != null'> AND t.creator=CAST(#{req.creatorId} AS varchar)</if>
            <if test='categories != null'> AND t.category_id IN <foreach collection='categories' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>
            <if test='req.creatorName != null and req.creatorName != ""'> AND (t.source_creator_name ILIKE CONCAT('%',#{req.creatorName},'%')
              <if test='creators != null and !creators.isEmpty()'> OR t.creator IN <foreach collection='creators' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>)</if>
            ORDER BY <choose>
              <when test='req.sortField == "assetCount"'>(SELECT COUNT(*) FROM dh_asset_tag_rel r JOIN dh_asset_data_asset a ON a.id=r.asset_id AND a.deleted=0 WHERE r.tag_id=t.id AND r.deleted=0
            """ + AssetTagRelMapper.SCOPE + """
              )</when><when test='req.sortField == "name"'>t.name</when><when test='req.sortField == "createTime"'>COALESCE(t.source_create_time,t.create_time)</when>
              <otherwise>CASE WHEN t.source_record_id ~ '^[0-9]+$' THEN t.source_record_id::numeric ELSE t.id::numeric END</otherwise></choose>
              <choose><when test='req.sortOrder == "asc"'>ASC</when><otherwise>DESC</otherwise></choose>,
              <choose><when test='req.sortField == "id"'>t.id <choose><when test='req.sortOrder == "asc"'>ASC</when><otherwise>DESC</otherwise></choose></when>
              <otherwise>t.source_sort_order ASC NULLS LAST,t.id DESC</otherwise></choose></script>
            """)
    IPage<AssetTagDO> scopedPage(IPage<AssetTagDO> page, @Param("req") AssetTagPageReqVO req,
            @Param("categories") Collection<Long> categories, @Param("creators") Collection<String> creators,
            @Param("userId") Long userId, @Param("deptId") Long deptId, @Param("actor") String actor,
            @Param("all") boolean all);

    /**
     * 按分类与名称查询资产标签。
     */
    default AssetTagDO selectByCategoryAndName(Long categoryId, String name) {
        return selectOne(AssetTagDO::getCategoryId, categoryId, AssetTagDO::getName, name);
    }

    /**
     * 查询启用列表。
     */
    default List<AssetTagDO> selectEnabledList() {
        return selectList(new LambdaQueryWrapperX<AssetTagDO>()
                .eq(AssetTagDO::getStatus, AssetTagDO.STATUS_ENABLED)
                .orderByAsc(AssetTagDO::getCategoryId)
                .orderByAsc(AssetTagDO::getName));
    }

    /**
     * 查询有序列表。
     */
    default List<AssetTagDO> selectOrderedList() {
        return selectList(new LambdaQueryWrapperX<AssetTagDO>()
                .orderByAsc(AssetTagDO::getCategoryId)
                .orderByAsc(AssetTagDO::getName));
    }

    /**
     * 查询资产标签分页。
     */
    default PageResult<AssetTagDO> selectPage(AssetTagPageReqVO reqVO, Collection<Long> categoryIds) {
        LambdaQueryWrapperX<AssetTagDO> wrapper = new LambdaQueryWrapperX<AssetTagDO>()
                .inIfPresent(AssetTagDO::getCategoryId, categoryIds)
                .eqIfPresent(AssetTagDO::getStatus, reqVO.getStatus())
                .eqIfPresent(AssetTagDO::getCreator,
                        reqVO.getCreatorId() == null ? null : String.valueOf(reqVO.getCreatorId()));
        if (StrUtil.isNotBlank(reqVO.getName())) {
            wrapper.and(query -> query.like(AssetTagDO::getName, reqVO.getName())
                    .or().like(AssetTagDO::getNearSynonyms, reqVO.getName())
                    .or().like(AssetTagDO::getSynonyms, reqVO.getName()));
        }
        wrapper.orderByDesc(AssetTagDO::getId);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 按分类编号查询资产标签数量。
     */
    default Long selectCountByCategoryId(Long categoryId) {
        return selectCount(AssetTagDO::getCategoryId, categoryId);
    }

    /**
     * 各类目下的标签数量：categoryId -> count
     */
    default Map<Long, Long> selectCountGroupByCategoryId() {
        List<Map<String, Object>> rows = selectMaps(Wrappers.<AssetTagDO>query()
                .select("category_id AS categoryId", "COUNT(*) AS cnt")
                .isNotNull("category_id")
                .groupBy("category_id"));
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.getOrDefault("categoryId", row.get("categoryid"));
            Object value = row.get("cnt");
            if (key instanceof Number keyNumber && value instanceof Number valueNumber) {
                result.put(keyNumber.longValue(), valueNumber.longValue());
            }
        }
        return result;
    }

}
