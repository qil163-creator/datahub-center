package cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileRunDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BatchFileRunMapper extends BaseMapperX<BatchFileRunDO> {

    /**
     * 统计批次文件运行的有效数量。
     *
     * @param taskId 任务编号
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT count(*) FROM dh_batch_file_run WHERE task_id=#{taskId} AND deleted=0 AND (status IN ('QUEUED','RUNNING') OR (status='UNKNOWN' AND resolved_time IS NULL))")
    long countActive(@Param("taskId") Long taskId);

    /**
     * 统计批次文件运行的保留文件数量。
     *
     * @param fileId 文件编号
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT count(*) FROM dh_batch_file_run r WHERE deleted=0 AND (status IN ('QUEUED','RUNNING') OR (status='UNKNOWN' AND resolved_time IS NULL) OR create_time>clock_timestamp()-interval '30 days') AND EXISTS (SELECT 1 FROM jsonb_array_elements(r.config_json::jsonb->'files') f WHERE f->>'id'=CAST(#{fileId} AS text))")
    long countRetainedFile(@Param("fileId") Long fileId);

    /**
     * 锁定并读取批次文件运行记录。
     *
     * @param id 记录编号
     * @return 批次文件运行
     */
    @Select("SELECT * FROM dh_batch_file_run WHERE id=#{id} AND deleted=0 FOR UPDATE")
    BatchFileRunDO lock(@Param("id") Long id);

    /**
     * 回收过期准备阶段运行，或将已开始执行的失联运行转入待核查状态。
     *
     * @return 恢复过期数值
     */
    @Update("UPDATE dh_batch_file_run SET status='UNKNOWN',error_message='工作者租约已失效，目标写入结果待人工核查',end_time=clock_timestamp(),update_time=clock_timestamp(),updater='batch-file-recovery' WHERE status='RUNNING' AND lease_until<clock_timestamp() AND deleted=0")
    int recoverExpired();

    /**
     * 查询批次文件运行的未反映未知。
     *
     * @return 批次文件运行的未反映未知结果集合
     */
    @Select("SELECT r.* FROM dh_batch_file_run r JOIN dh_batch_file_task t ON t.id=r.task_id AND t.last_run_id=r.id AND t.deleted=0 WHERE r.deleted=0 AND r.status='UNKNOWN' AND r.resolved_time IS NULL AND t.last_status<>'UNKNOWN' ORDER BY r.id LIMIT 100")
    java.util.List<BatchFileRunDO> selectUnreflectedUnknown();

    /**
     * 更新批次文件运行的认领。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @return 认领数值
     */
    @Update("UPDATE dh_batch_file_run SET status='RUNNING',lease_token=#{token},lease_until=clock_timestamp()+interval '2 minutes',start_time=clock_timestamp(),update_time=clock_timestamp(),updater=CAST(execution_user_id AS text) WHERE id=#{id} AND status='QUEUED' AND deleted=0")
    int claim(@Param("id") Long id, @Param("token") String token);

    /**
     * 更新批次文件运行的心跳。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @return 心跳数值
     */
    @Update("UPDATE dh_batch_file_run SET lease_until=clock_timestamp()+interval '2 minutes' WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until>clock_timestamp() AND deleted=0")
    int heartbeat(@Param("id") Long id, @Param("token") String token);

    /**
     * 更新批次文件运行的进度。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @param items 项
     * @param success 成功
     * @param failed 失败
     * @param rows 待处理的行数据
     * @return 进度数值
     */
    @Update("UPDATE dh_batch_file_run SET items_json=#{items},success_count=#{success},failed_count=#{failed},written_rows=#{rows},update_time=clock_timestamp(),updater=CAST(execution_user_id AS text) WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until>clock_timestamp() AND deleted=0")
    int progress(@Param("id") Long id, @Param("token") String token, @Param("items") String items,
            @Param("success") int success, @Param("failed") int failed, @Param("rows") long rows);

    /**
     * 更新批次文件运行的结束。
     *
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @param status 目标状态
     * @param items 项
     * @param success 成功
     * @param failed 失败
     * @param rows 待处理的行数据
     * @param error 失败原因
     * @return 结束数值
     */
    @Update("UPDATE dh_batch_file_run SET status=#{status},items_json=#{items},success_count=#{success},failed_count=#{failed},written_rows=#{rows},error_message=#{error},end_time=clock_timestamp(),lease_until=NULL,update_time=clock_timestamp(),updater=CAST(execution_user_id AS text) WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until>clock_timestamp() AND deleted=0")
    int finish(@Param("id") Long id, @Param("token") String token, @Param("status") String status,
            @Param("items") String items, @Param("success") int success, @Param("failed") int failed,
            @Param("rows") long rows, @Param("error") String error);
}
