package cn.iocoder.yudao.module.dataforge.dal.mysql.job;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.JobTaskDO;
import org.apache.ibatis.annotations.*;
/** 作业业务持久化入口。 */
@Mapper public interface JobTaskMapper extends BaseMapperX<JobTaskDO> {
    /** 只在领域短事务内锁定作业；图与调度快照经既有 JSONB 处理器读取。 */
    @Select("SELECT * FROM dh_dataforge_job WHERE id=#{id} AND deleted=0 FOR UPDATE")
    @ResultMap("mybatis-plus_JobTaskDO")
    JobTaskDO selectForUpdate(@Param("id") Long id);

    /** 选择稳定的任务摘要；运行绑定从受管记录解析，缺少绑定时明确不可执行。 */
    @Select("SELECT id,'INTEGRATION' kind,name,category_id,(SELECT c.name FROM dh_dataforge_integration_category c WHERE c.id=t.category_id AND c.deleted=0) category_name,engine task_type,status,description,"
            + "coalesce((to_jsonb(t)->>'ds_definition_code') IS NOT NULL AND status=1 AND mode='OFFLINE' "
            + "AND (to_jsonb(t)->>'ds_project_code')=#{projectCode}::text,false) executable "
            + "FROM dh_dataforge_integration_task t WHERE deleted=0 ORDER BY id")
    java.util.List<cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.TaskOption> integrationOptions(@Param("projectCode") Long projectCode);

    /** 开发任务保留独立冻结身份通道，目录中仅提供安全摘要。 */
    @Select("SELECT id,'DEVELOPMENT' kind,name,dir_id category_id,(SELECT c.name FROM dh_dataforge_dev_dir c WHERE c.id=t.dir_id AND c.deleted=0) category_name,task_type,db_type,status,description,(status=1 AND current_version_id IS NOT NULL) executable FROM dh_dataforge_dev_task t WHERE deleted=0 ORDER BY id")
    java.util.List<cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.TaskOption> developmentOptions();
}
