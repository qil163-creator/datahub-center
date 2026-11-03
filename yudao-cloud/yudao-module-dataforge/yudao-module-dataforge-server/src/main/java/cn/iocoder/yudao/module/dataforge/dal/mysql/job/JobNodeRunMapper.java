package cn.iocoder.yudao.module.dataforge.dal.mysql.job;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.JobNodeRunDO;
import org.apache.ibatis.annotations.Mapper;
/** 父子关系按稳定节点编码存储，不使用名称或最近实例认领。 */
@Mapper public interface JobNodeRunMapper extends BaseMapperX<JobNodeRunDO> { }
