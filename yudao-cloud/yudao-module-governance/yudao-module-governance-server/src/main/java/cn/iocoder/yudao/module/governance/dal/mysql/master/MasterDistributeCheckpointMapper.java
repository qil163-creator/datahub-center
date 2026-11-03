package cn.iocoder.yudao.module.governance.dal.mysql.master;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 主数据推送已确认记录摘要，原数据和认证信息不进入检查点。 */
@Mapper
public interface MasterDistributeCheckpointMapper {

    record Checkpoint(String rowKeyHash, String payloadHash) {

    }

    /**
     * 按分发运行和目标标识查找已保存的检查点。
     */
    @Select("<script>SELECT row_key_hash,payload_hash FROM dh_governance_master_distribute_checkpoint "
            + "WHERE distribute_id=#{task} AND deleted=0 AND row_key_hash IN "
            + "<foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key}</foreach></script>")
    List<Checkpoint> find(@Param("task") Long task, @Param("keys") List<String> keys);

    /**
     * 按业务条件新增主数据分发检查点的确认状态或数据，返回受影响行数。
     */
    @Insert("<script>INSERT INTO dh_governance_master_distribute_checkpoint(distribute_id,row_key_hash,payload_hash,run_id,creator,updater) VALUES "
            + "<foreach collection='rows' item='row' separator=','>(#{task},#{row.rowKeyHash},#{row.payloadHash},#{run},#{actor},#{actor})</foreach> "
            + "ON CONFLICT(distribute_id,row_key_hash) DO UPDATE SET payload_hash=excluded.payload_hash,run_id=excluded.run_id,"
            + "updater=excluded.updater,update_time=clock_timestamp(),deleted=0</script>")
    int confirm(@Param("task") Long task, @Param("run") Long run, @Param("rows") List<Checkpoint> rows,
            @Param("actor") String actor);

    /**
     * 按业务条件删除主数据分发检查点的清除状态或数据，返回受影响行数。
     */
    @Delete("DELETE FROM dh_governance_master_distribute_checkpoint WHERE distribute_id=#{task}")
    int clear(@Param("task") Long task);

    /**
     * 按业务条件删除主数据分发检查点的清除已处理运行状态或数据，返回受影响行数。
     */
    @Delete("DELETE FROM dh_governance_master_distribute_checkpoint WHERE distribute_id=#{task} AND run_id=#{run}")
    int clearResolvedRun(@Param("task") Long task, @Param("run") Long run);
}
