package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EtlTargetBatchMapper extends BaseMapperX<EtlTargetBatchDO> {

    /** 仅在全局扫描标识信息；调用方处理前校验各自的业务记录。 */

    @Select("SELECT id FROM dh_dataforge_etl_target_batch WHERE deleted=0 AND "
            + "((attempt_count<10 AND commit_status IN ('READY','UNKNOWN','RETRY') AND (next_attempt_at IS NULL OR next_attempt_at<=#{now})) "
            + "OR (commit_status='COMMITTING' AND lease_until<=#{now}) "
            + "OR (commit_status='COMMITTED' AND quality_status IN ('PENDING','RUNNING'))) ORDER BY id LIMIT 20")
    List<EtlTargetBatchDO> selectPending(@Param("now") LocalDateTime now);

    /**
     * 查询ETL 目标批次的恢复候选。
     *
     * @return ETL 目标批次的恢复候选结果集合
     */
    @Select("SELECT DISTINCT r.id FROM dh_dataforge_etl_record r JOIN dh_dataforge_etl_target_batch b "
            + "ON b.record_id=r.id WHERE r.deleted=0 AND b.deleted=0 AND r.run_status=6 "
            + "AND b.commit_status='COMMITTED' ORDER BY r.id LIMIT 20")
    List<cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO> selectRecoveryCandidates();

    /**
     * 新增ETL 目标批次的操作记录。
     *
     * @param batchId 批次编号
     * @param actorId 操作人编号
     * @param action 待执行的操作
     * @param reason 操作原因
     * @param beforeState 变更前状态
     * @return 新增操作数值
     */
    @Insert("INSERT INTO dh_dataforge_etl_target_action(batch_id,actor_id,action,reason,before_state) "
            + "VALUES(#{batchId},#{actorId},#{action},#{reason},#{beforeState})")
    int insertAction(@Param("batchId") Long batchId, @Param("actorId") Long actorId,
            @Param("action") String action, @Param("reason") String reason, @Param("beforeState") String beforeState);
}
