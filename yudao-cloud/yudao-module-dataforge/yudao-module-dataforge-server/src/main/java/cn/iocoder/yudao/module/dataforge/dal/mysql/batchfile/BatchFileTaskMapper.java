package cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileTaskDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BatchFileTaskMapper extends BaseMapperX<BatchFileTaskDO> {

    /**
     * 锁定并读取批次文件任务记录。
     *
     * @param id 记录编号
     * @return 批次文件任务
     */
    @Select("SELECT * FROM dh_batch_file_task WHERE id=#{id} AND deleted=0 FOR UPDATE")
    BatchFileTaskDO lock(@Param("id") Long id);
}
