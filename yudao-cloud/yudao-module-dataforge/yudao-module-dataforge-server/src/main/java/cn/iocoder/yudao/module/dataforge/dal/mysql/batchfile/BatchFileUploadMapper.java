package cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileUploadDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BatchFileUploadMapper extends BaseMapperX<BatchFileUploadDO> {

    /**
     * 锁定并读取批次文件上传记录。
     *
     * @param id 记录编号
     * @return 批次文件上传
     */
    @Select("SELECT * FROM dh_batch_file_upload WHERE id=#{id} AND deleted=0 FOR UPDATE")
    BatchFileUploadDO lock(@Param("id") Long id);

    /**
     * 更新批次文件上传的就绪。
     *
     * @param id 记录编号
     * @param fileId 文件编号
     * @param actor 操作人编号
     * @return 就绪数值
     */
    @Update("UPDATE dh_batch_file_upload SET storage_file_id=#{fileId},state='READY',updater=#{actor},update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 AND state='PENDING' AND task_id IS NULL AND expires_at>clock_timestamp()")
    int ready(@Param("id") Long id, @Param("fileId") Long fileId, @Param("actor") String actor);

    /**
     * 更新批次文件上传的完整清理。
     *
     * @param id 记录编号
     * @return 完整清理数值
     */
    @Update("UPDATE dh_batch_file_upload SET state='CLEANED',updater='batch-file-cleanup',update_time=clock_timestamp() WHERE id=#{id} AND deleted=0 AND state='CLEANUP_PENDING' AND task_id IS NULL")
    int completeCleanup(@Param("id") Long id);

    /**
     * 查询批次文件上传的过期。
     *
     * @param afterId 变更后编号
     * @return 批次文件上传的过期结果集合
     */
    @Select("SELECT * FROM dh_batch_file_upload WHERE deleted=0 AND task_id IS NULL AND state<>'CLEANED' AND expires_at<=clock_timestamp() AND id>#{afterId} ORDER BY id LIMIT 50")
    java.util.List<BatchFileUploadDO> selectExpired(@Param("afterId") long afterId);
}
