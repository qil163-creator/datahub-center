package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityEtlTaskCreationDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface QualityEtlTaskCreationMapper {

    /**
     * 按来源标识查找 ETL 质量任务创建记录。
     */
    @Select("SELECT * FROM dh_governance_quality_etl_task_creation WHERE request_id=#{requestId} AND deleted=0")
    QualityEtlTaskCreationDO find(@Param("requestId") String requestId);

    /**
     * 按业务条件新增质量ETL任务创建的预留状态或数据，返回受影响行数。
     */
    @Insert("""
            INSERT INTO dh_governance_quality_etl_task_creation
              (request_id,request_hash,actor_id,task_name,task_status,definition_hash,target_json,creator,updater)
            VALUES(#{row.requestId},#{row.requestHash},#{row.actorId},#{row.taskName},#{row.taskStatus},
              #{row.definitionHash},CAST(#{row.targetJson} AS jsonb),CAST(#{row.actorId} AS text),CAST(#{row.actorId} AS text))
            ON CONFLICT (request_id) DO NOTHING
            """)
    int reserve(@Param("row") QualityEtlTaskCreationDO row);

    /**
     * 按指定条件读取并锁定质量ETL任务创建记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_quality_etl_task_creation WHERE request_id=#{requestId} AND deleted=0 FOR UPDATE")
    QualityEtlTaskCreationDO lock(@Param("requestId") String requestId);

    /**
     * 按业务条件更新质量ETL任务创建的结束状态或数据，返回受影响行数。
     */
    @Update("""
            UPDATE dh_governance_quality_etl_task_creation SET task_id=#{taskId},check_object_id=#{objectId}
             WHERE id=#{id} AND task_id IS NULL AND check_object_id IS NULL AND deleted=0
            """)
    int finish(@Param("id") long id, @Param("taskId") long taskId, @Param("objectId") long objectId);
}
