package cn.iocoder.yudao.module.bpm.dal.mysql.task;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Serializes an internal business-key start inside the same PostgreSQL transaction as Flowable. */
@Mapper
public interface BpmRpcStartLockMapper {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lock(@Param("key") String key);
}
