package cn.iocoder.yudao.module.supply.dal.mysql.appapi;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantOpDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SupplyGrantOpMapper extends BaseMapperX<SupplyGrantOpDO> {

    /**
     * 查询供应授权操作的历史未知到期。
     *
     * @param limit 读取或处理上限
     * @return 供应授权操作的历史未知到期结果集合
     */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT * FROM dh_supply_grant_op WHERE deleted=0
              AND protocol_version=0 AND result_status='UNKNOWN'
              AND error_code IS DISTINCT FROM 'LEGACY_REVIEW_REQUIRED'
              AND (next_retry_time IS NULL OR next_retry_time <= clock_timestamp() AT TIME ZONE 'Asia/Shanghai')
            ORDER BY id LIMIT #{limit}
            """)
    List<SupplyGrantOpDO> selectLegacyUnknownDue(@Param("limit") int limit);

    /**
     * 更新供应授权操作的标记历史未知对应复核。
     *
     * @param id 记录编号
     * @return 标记历史未知对应复核数值
     */
    @org.apache.ibatis.annotations.Update("""
            UPDATE dh_supply_grant_op SET error_code='LEGACY_REVIEW_REQUIRED', next_retry_time=NULL,
              update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{id} AND deleted=0 AND protocol_version=0
              AND result_status='UNKNOWN' AND error_code IS DISTINCT FROM 'LEGACY_REVIEW_REQUIRED'
            """)
    int markLegacyUnknownForReview(@Param("id") long id);

    /** 回执只能插入，不得用当前聚合反向重构或覆盖旧回执。 */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO dh_supply_grant_op
              (source_system,operation_id,payload_hash,aggregate_key,target_status,result_status,
               grant_id,error_code,protocol_version,source_revision,aggregate_revision,command_type,response_json)
            VALUES (#{sourceSystem},#{operationId},#{payloadHash},#{aggregateKey},#{targetStatus},
              #{resultStatus},#{grantId},#{errorCode},2,#{sourceRevision},#{aggregateRevision},
              #{commandType},CAST(#{responseJson,jdbcType=VARCHAR} AS jsonb))
            """)
    int insertSourceReceipt(SupplyGrantOpDO receipt);

    // NOT_SUPPORTED 挂起/恢复的会话可能缓存首次未命中；回执必须读数据库已提交事实。

    /**
     * 查询供应授权操作的来源操作。
     *
     * @param sourceSystem 来源系统
     * @param operationId 操作编号
     * @return 供应授权操作
     */
    @org.apache.ibatis.annotations.Options(
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
            SELECT * FROM dh_supply_grant_op WHERE source_system=#{sourceSystem} AND operation_id=#{operationId}
            """)
    SupplyGrantOpDO selectSourceOperation(@Param("sourceSystem") String sourceSystem,
            @Param("operationId") String operationId);

    /**
     * 按操作查询供应授权操作。
     *
     * @param sourceSystem 来源系统
     * @param operationId 操作编号
     * @return 供应授权操作
     */
    default SupplyGrantOpDO selectByOperation(String sourceSystem, String operationId) {
        return selectOne(new LambdaQueryWrapperX<SupplyGrantOpDO>()
                .eq(SupplyGrantOpDO::getSourceSystem, sourceSystem)
                .eq(SupplyGrantOpDO::getOperationId, operationId)
                .last("LIMIT 1"));
    }

    /**
     * 查询未知到期。
     *
     * @param now 当前时间
     * @param limit 读取或处理上限
     * @return 未知到期结果集合
     */
    default List<SupplyGrantOpDO> selectUnknownDue(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<SupplyGrantOpDO>()
                .eq(SupplyGrantOpDO::getResultStatus, SupplyGrantOpDO.UNKNOWN)
                .le(SupplyGrantOpDO::getNextRetryTime, now)
                .orderByAsc(SupplyGrantOpDO::getId)
                .last("LIMIT " + Math.max(limit, 1)));
    }
}
