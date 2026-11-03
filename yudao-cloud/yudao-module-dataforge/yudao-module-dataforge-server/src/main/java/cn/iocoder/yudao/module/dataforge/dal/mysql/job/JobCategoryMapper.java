package cn.iocoder.yudao.module.dataforge.dal.mysql.job;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.JobCategoryDO;
import org.apache.ibatis.annotations.*;
/** 作业业务持久化入口。 */
@Mapper public interface JobCategoryMapper extends BaseMapperX<JobCategoryDO> {
    /** 类目修改在真实事务中获取平台树锁。 */
    @Select("SELECT true FROM (SELECT pg_advisory_xact_lock(255030)) locked") boolean lockTree();
}
