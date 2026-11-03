package cn.iocoder.yudao.module.dataforge.dal.mysql.fileresource;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fileresource.FileResourceOperationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileResourceOperationMapper extends BaseMapperX<FileResourceOperationDO> {

    /**
     * 统计文件资源操作的今日上传数量。
     *
     * @param connectionId 连接编号
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT count(*) FROM dh_dataforge_file_operation WHERE connection_id = #{connectionId} AND action = 'UPLOAD' AND status = 'SUCCESS' AND create_time >= CURRENT_DATE AND deleted = 0")
    Long countTodayUploads(@Param("connectionId") Long connectionId);
}

