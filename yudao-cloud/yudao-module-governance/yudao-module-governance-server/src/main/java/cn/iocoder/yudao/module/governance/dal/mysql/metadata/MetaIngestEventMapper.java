package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaIngestEventDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MetaIngestEventMapper extends BaseMapperX<MetaIngestEventDO> {

    /** 事务级目标锁；锁键按业务目标生成，不在持锁范围执行 RPC。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockTarget(@Param("key") String key);

    /**
     * 查询事件。
     */
    default MetaIngestEventDO selectEvent(String source, String eventId) {
        return selectOne(new LambdaQueryWrapperX<MetaIngestEventDO>()
                .eq(MetaIngestEventDO::getSource, source).eq(MetaIngestEventDO::getEventId, eventId));
    }

    /**
     * 查询元数据接收事件最新。
     */
    default MetaIngestEventDO selectLatest(String source, String targetKey) {
        return selectOne(new LambdaQueryWrapperX<MetaIngestEventDO>()
                .eq(MetaIngestEventDO::getSource, source).eq(MetaIngestEventDO::getTargetKey, targetKey)
                .orderByDesc(MetaIngestEventDO::getRevision).last("LIMIT 1"));
    }

    /**
     * 查询修订号。
     */
    default MetaIngestEventDO selectRevision(String source, String targetKey, Long revision) {
        return selectOne(new LambdaQueryWrapperX<MetaIngestEventDO>()
                .eq(MetaIngestEventDO::getSource, source).eq(MetaIngestEventDO::getTargetKey, targetKey)
                .eq(MetaIngestEventDO::getRevision, revision));
    }
}
