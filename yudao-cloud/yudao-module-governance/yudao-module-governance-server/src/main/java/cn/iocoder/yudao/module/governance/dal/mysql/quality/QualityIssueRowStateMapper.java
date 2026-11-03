package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityIssueRowStateDO;
import org.apache.ibatis.annotations.*;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QualityIssueRowStateMapper extends BaseMapperX<QualityIssueRowStateDO> {

    /**
     * 查询质量问题数据行状态。
     */
    default List<QualityIssueRowStateDO> selectStates(Long issue, Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<QualityIssueRowStateDO>()
                .eq(QualityIssueRowStateDO::getIssueId, issue).in(QualityIssueRowStateDO::getRowKey, keys));
    }

    /**
     * 按业务条件新增质量问题数据行状态的确保状态或数据，返回受影响行数。
     */
    @Insert("""
            INSERT INTO dh_governance_quality_issue_row_state
              (issue_id, row_key, state, revision, remark, creator, updater)
            VALUES (#{issue}, #{key}, 'UNPROCESSED', 0, '', #{actor}, #{actor})
            ON CONFLICT (issue_id, row_key) DO NOTHING
            """)
    int ensureState(@Param("issue") Long issue, @Param("key") String key, @Param("actor") String actor);

    /**
     * 按业务条件更新质量问题数据行状态的比对与集合状态或数据，返回受影响行数。
     */
    @Update("""
            UPDATE dh_governance_quality_issue_row_state
            SET state = CASE WHEN #{action} = 'IGNORE' THEN 'IGNORED' ELSE state END,
                remark = CASE WHEN #{action} = 'REMARK' THEN #{remark} ELSE remark END,
                revision = revision + 1, updater = #{actor}, update_time = CURRENT_TIMESTAMP
            WHERE issue_id = #{issue} AND row_key = #{key} AND revision = #{revision} AND deleted = 0
            """)
    int compareAndSet(@Param("issue") Long issue, @Param("key") String key,
            @Param("revision") int revision, @Param("action") String action,
            @Param("remark") String remark, @Param("actor") String actor);

    /**
     * 按业务条件新增质量问题数据行状态的记录操作状态或数据，返回受影响行数。
     */
    @Insert("""
            INSERT INTO dh_governance_quality_issue_row_audit
              (issue_id, row_key, actor_id, action, before_state, after_state,
               before_revision, after_revision, remark_digest, correlation_id)
            VALUES (#{issue}, #{key}, #{actor}, #{action}, #{beforeState},
                    CASE WHEN #{action} = 'IGNORE' THEN 'IGNORED' ELSE #{beforeState} END,
                    #{revision}, #{revision} + 1, #{remarkDigest}, #{correlation})
            """)
    int recordAction(@Param("issue") Long issue, @Param("key") String key,
            @Param("actor") Long actor, @Param("action") String action,
            @Param("beforeState") String beforeState,
            @Param("revision") int revision, @Param("remarkDigest") String remarkDigest,
            @Param("correlation") String correlation);
}
