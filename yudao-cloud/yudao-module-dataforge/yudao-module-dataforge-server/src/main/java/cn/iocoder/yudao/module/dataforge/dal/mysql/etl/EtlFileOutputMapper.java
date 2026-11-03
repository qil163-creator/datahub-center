package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlFileOutputDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface EtlFileOutputMapper extends BaseMapperX<EtlFileOutputDO> {

    /** 全局只返回候选身份，随后必须校验其业务归属。活跃运行绝不按文件年龄清理。 */

    @Select("SELECT f.id FROM dh_dataforge_etl_file_output f "
            + "WHERE f.deleted=0 AND f.recovery_status IN ('PENDING','RETRY','CLAIMED') "
            + "AND (f.next_attempt_at IS NULL OR f.next_attempt_at<=clock_timestamp()) "
            + "AND (f.recovery_lease_until IS NULL OR f.recovery_lease_until<=clock_timestamp()) "
            + "AND (f.retention_status='KEEP' OR (f.retention_status='REQUESTED' AND f.retire_after<=clock_timestamp())) "
            + "AND NOT EXISTS (SELECT 1 FROM dh_dataforge_etl_record r WHERE r.id=f.record_id "
            + "AND r.execution_token=f.run_token AND r.lease_until>clock_timestamp()) "
            + "ORDER BY f.id LIMIT 20")
    List<EtlFileOutputDO> candidates();

    /**
     * 锁定并读取ETL 文件输出记录。
     *
     * @param id 记录编号
     * @return ETL 文件输出
     */
    @Select("SELECT * FROM dh_dataforge_etl_file_output WHERE id=#{id} AND deleted=0 FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    EtlFileOutputDO lock(@Param("id") Long id);

    /**
     * 查询ETL 文件输出的实时运行。
     *
     * @param recordId 记录编号
     * @param runToken 运行令牌
     * @return 实时运行数值
     */
    @Select("SELECT count(*) FROM dh_dataforge_etl_record WHERE id=#{recordId} "
            + "AND execution_token=#{runToken} AND lease_until>clock_timestamp()")
    long liveRun(@Param("recordId") Long recordId, @Param("runToken") String runToken);

    /** 仅检查已知台账引用，无法据此证明受管存储之外不存在其他路径。 */
    @Select("SELECT count(*) FROM dh_dataforge_etl_file_output WHERE deleted=0 AND id<>#{id} "
            + "AND storage_identity=#{storageIdentity} AND temporary_file_key=#{fileKey} AND retention_status<>'PURGED'")
    long otherIdentityReferences(@Param("id") Long id, @Param("storageIdentity") String storageIdentity,
            @Param("fileKey") String fileKey);

    /**
     * 查询ETL 文件输出的退役请求修订号。
     *
     * @param id 记录编号
     * @return 退役请求修订号数值
     */
    @Select("SELECT revision FROM dh_dataforge_etl_file_audit WHERE output_id=#{id} "
            + "AND action='RETIRE_REQUEST' ORDER BY revision LIMIT 1")
    Long retirementRequestRevision(@Param("id") Long id);

    /**
     * 新增ETL 文件输出的审计记录。
     *
     * @param output 输出目标
     * @param actor 操作人编号
     * @param action 待执行的操作
     * @param before 变更前状态
     * @param after 变更后状态
     * @param revision 修订号
     * @param reason 操作原因
     * @return 审计数值
     */
    @Insert("INSERT INTO dh_dataforge_etl_file_audit (output_id,actor_id,action,before_state,after_state,revision,reason,creator,updater) "
            + "VALUES (#{output},#{actor},#{action},#{before},#{after},#{revision},#{reason},CAST(#{actor} AS text),CAST(#{actor} AS text))")
    int audit(@Param("output") Long output, @Param("actor") Long actor,
            @Param("action") String action, @Param("before") String before, @Param("after") String after,
            @Param("revision") Long revision, @Param("reason") String reason);
}
