package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlQualityBatchDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EtlQualityBatchMapper extends BaseMapperX<EtlQualityBatchDO> {

    /** 仅供内部恢复扫描，逐项处理业务记录后才能读写和调用 RPC。 */

    @Select("SELECT * FROM dh_dataforge_etl_quality_batch WHERE deleted = 0 "
            + "AND ((attempt_count < 10 AND registration_status IN ('PENDING','RETRY') AND (next_attempt_at IS NULL OR next_attempt_at <= #{now})) "
            + "OR (registration_status='REGISTERING' AND lease_until <= #{now})) ORDER BY id LIMIT 20")
    List<EtlQualityBatchDO> selectDeliveryCandidates(@Param("now") LocalDateTime now);

    /**
     * 统计ETL质量批次的流程证据数量。
     *
     * @param pipelineId 流程编号
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT count(*) FROM dh_dataforge_etl_quality_batch b JOIN dh_dataforge_etl_record r ON r.id=b.record_id "
            + "AND 1=1 WHERE r.pipeline_id=#{pipelineId} AND b.deleted=0")
    long countPipelineEvidence(@Param("pipelineId") Long pipelineId);

    /**
     * 新增ETL质量批次的重试审计记录。
     *
     * @param batchId 批次编号
     * @param actorId 操作人编号
     * @param attempts 尝试
     * @return 新增重试审计数值
     */
    @Insert("INSERT INTO dh_dataforge_etl_quality_retry (batch_id,actor_id,previous_attempts,creator,updater) "
            + "VALUES (#{batchId},#{actorId},#{attempts},CAST(#{actorId} AS text),CAST(#{actorId} AS text))")
    int insertRetryAudit(@Param("batchId") Long batchId,
            @Param("actorId") Long actorId, @Param("attempts") int attempts);
}
