package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterMergeXrefDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MasterMergeXrefMapper extends BaseMapperX<MasterMergeXrefDO> {

    /** 黄金记录汇总行 */
    record GoldenSummary(String goldenKey, Long mergedCount, LocalDateTime lastMergedAt) {
    }

    @Select("SELECT golden_key, count(*) AS merged_count, max(merged_at) AS last_merged_at "
            + "FROM dh_governance_master_merge_xref WHERE model_id = #{modelId} AND deleted = 0 "
            + "GROUP BY golden_key ORDER BY max(merged_at) DESC, golden_key LIMIT #{limit} OFFSET #{offset}")
    List<GoldenSummary> selectGoldenPage(@Param("modelId") Long modelId, @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("SELECT count(DISTINCT golden_key) FROM dh_governance_master_merge_xref "
            + "WHERE model_id = #{modelId} AND deleted = 0")
    long countGolden(@Param("modelId") Long modelId);

    default List<MasterMergeXrefDO> selectSources(Long modelId, List<String> goldenKeys, int limitPerPage) {
        if (goldenKeys.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<MasterMergeXrefDO>()
                .eq(MasterMergeXrefDO::getModelId, modelId)
                .in(MasterMergeXrefDO::getGoldenKey, goldenKeys)
                .orderByDesc(MasterMergeXrefDO::getMergedAt)
                .orderByDesc(MasterMergeXrefDO::getId)
                .last("LIMIT " + Math.max(1, limitPerPage)));
    }

    /** 黄金记录本身被合并进其他记录时，其来源随之归入新的黄金记录 */
    default int repoint(Long modelId, String fromGoldenKey, String toGoldenKey) {
        return update(null, Wrappers.lambdaUpdate(MasterMergeXrefDO.class)
                .eq(MasterMergeXrefDO::getModelId, modelId)
                .eq(MasterMergeXrefDO::getGoldenKey, fromGoldenKey)
                .set(MasterMergeXrefDO::getGoldenKey, toGoldenKey));
    }
}
