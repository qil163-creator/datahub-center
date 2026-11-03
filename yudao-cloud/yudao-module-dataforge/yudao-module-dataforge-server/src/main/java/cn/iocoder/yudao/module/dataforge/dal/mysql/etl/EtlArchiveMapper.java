package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlArchiveDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EtlArchiveMapper extends BaseMapperX<EtlArchiveDO> {

    /**
     * 新增ETL归档的操作记录。
     *
     * @param record 运行记录
     * @param actor 操作人编号
     * @param action 待执行的操作
     * @param reason 操作原因
     * @return 操作数值
     */
    @Insert("INSERT INTO dh_dataforge_etl_archive_action(record_id,actor_id,action,reason) VALUES(#{record},#{actor},#{action},#{reason})")
    int action(@Param("record") Long record, @Param("actor") Long actor, @Param("action") String action,
            @Param("reason") String reason);
}
