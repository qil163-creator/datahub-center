package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectInstancePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-元数据采集实例 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaCollectInstanceMapper extends BaseMapperX<MetaCollectInstanceDO> {

    /**
     * 锁定队列。
     */
    @org.apache.ibatis.annotations.Select("SELECT 1 FROM pg_advisory_xact_lock(1900002102)")
    Integer lockQueue();

    /**
     * 按指定条件读取并锁定元数据采集实例记录，供事务内一致性校验或修改使用。
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM dh_governance_meta_collect_instance WHERE id=#{id} AND deleted=0 FOR UPDATE")
    MetaCollectInstanceDO lockInstance(@org.apache.ibatis.annotations.Param("id") Long id);

    /**
     * 按业务条件更新元数据采集实例的续期状态或数据，返回受影响行数。
     */
    @org.apache.ibatis.annotations.Update("UPDATE dh_governance_meta_collect_instance SET lease_until=CURRENT_TIMESTAMP + INTERVAL '5 minutes' WHERE id=#{id} AND execution_token=#{token} AND status=1 AND deleted=0 AND lease_until>CURRENT_TIMESTAMP")
    int renew(@org.apache.ibatis.annotations.Param("id") Long id,
            @org.apache.ibatis.annotations.Param("token") String token);

    /**
     * 查询元数据采集实例分页。
     */
    default PageResult<MetaCollectInstanceDO> selectPage(MetaCollectInstancePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MetaCollectInstanceDO>()
                .eqIfPresent(MetaCollectInstanceDO::getTaskId, reqVO.getTaskId())
                .likeIfPresent(MetaCollectInstanceDO::getTaskName, reqVO.getName())
                .eqIfPresent(MetaCollectInstanceDO::getDatasourceId, reqVO.getDatasourceId())
                .eqIfPresent(MetaCollectInstanceDO::getSourceSystemId, reqVO.getSourceSystemId())
                .eqIfPresent(MetaCollectInstanceDO::getCollectType, reqVO.getCollectType())
                .betweenIfPresent(MetaCollectInstanceDO::getStartTime, reqVO.getStartTime())
                .eqIfPresent(MetaCollectInstanceDO::getStatus, reqVO.getStatus())
                .eqIfPresent(MetaCollectInstanceDO::getTriggerType, reqVO.getTriggerType())
                .betweenIfPresent(MetaCollectInstanceDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(MetaCollectInstanceDO::getId));
    }

    /**
     * 查询任务是否存在运行中的实例（进程内防并发之外的数据库级兜底）
     */
    default MetaCollectInstanceDO selectRunningByTaskId(Long taskId) {
        return selectOne(new LambdaQueryWrapperX<MetaCollectInstanceDO>()
                .eq(MetaCollectInstanceDO::getTaskId, taskId)
                .eq(MetaCollectInstanceDO::getStatus, MetaCollectInstanceDO.STATUS_RUNNING)
                .last("LIMIT 1"));
    }

}
