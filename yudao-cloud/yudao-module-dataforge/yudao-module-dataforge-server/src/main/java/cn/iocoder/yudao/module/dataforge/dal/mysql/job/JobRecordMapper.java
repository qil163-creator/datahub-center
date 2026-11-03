package cn.iocoder.yudao.module.dataforge.dal.mysql.job;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.JobRecordDO;
import org.apache.ibatis.annotations.*;
/** 作业业务持久化入口。 */
@Mapper public interface JobRecordMapper extends BaseMapperX<JobRecordDO> {
}
