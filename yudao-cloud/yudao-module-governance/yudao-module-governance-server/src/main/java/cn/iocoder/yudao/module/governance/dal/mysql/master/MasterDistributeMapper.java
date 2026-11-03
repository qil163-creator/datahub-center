package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 主数据派发任务 Mapper
 *
 * @author Li
 */
@Mapper
public interface MasterDistributeMapper extends BaseMapperX<MasterDistributeDO> {

    record CategoryTaskCount(Long id, Long taskCount) {}

    @Select("SELECT category_id AS id, count(*) AS task_count FROM dh_governance_master_distribute "
            + "WHERE deleted = 0 AND category_id IS NOT NULL GROUP BY category_id")
    List<CategoryTaskCount> selectCategoryTaskCounts();


    /**
     * 按指定条件读取并锁定主数据分发记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_master_distribute WHERE id=#{id} AND deleted=0 FOR UPDATE")
    @ResultMap("mybatis-plus_MasterDistributeDO")
    MasterDistributeDO lockOwned(@Param("id") Long id);

    /** 全局控制面只读发现；后续写入必须通过领域状态机。 */
    @Select("SELECT t.* FROM dh_governance_master_distribute t WHERE t.deleted=0 AND t.status=1 AND t.task_type=2 "
            + "AND t.next_run_time<=clock_timestamp() AND NOT EXISTS (SELECT 1 FROM dh_governance_master_distribute_log r "
            + "WHERE r.distribute_id=t.id AND r.deleted=0 AND r.run_status IN (1,2,5)) "
            + "ORDER BY t.next_run_time,t.id LIMIT 100")
    @ResultMap("mybatis-plus_MasterDistributeDO")
    List<MasterDistributeDO> dueCandidates();

    /**
     * 查询主数据分发分页。
     */
    default PageResult<MasterDistributeDO> selectPage(MasterDistributePageReqVO reqVO) {
        return selectPage(reqVO, (List<Long>) null);
    }

    default PageResult<MasterDistributeDO> selectPage(MasterDistributePageReqVO reqVO, List<Long> categoryIds) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MasterDistributeDO>()
                .inIfPresent(MasterDistributeDO::getCategoryId, categoryIds)
                .likeIfPresent(MasterDistributeDO::getName, reqVO.getName())
                .eqIfPresent(MasterDistributeDO::getDistributeType, reqVO.getDistributeType())
                .eqIfPresent(MasterDistributeDO::getStatus, reqVO.getStatus())
                .eqIfPresent(MasterDistributeDO::getMasterModelId, reqVO.getMasterModelId())
                .apply(reqVO.getLatestRunStatus() != null,
                        "(SELECT r.run_status FROM dh_governance_master_distribute_log r "
                                + "WHERE r.distribute_id=dh_governance_master_distribute.id AND r.deleted=0 ORDER BY r.id DESC LIMIT 1) = {0}",
                        reqVO.getLatestRunStatus())
                .orderByDesc(MasterDistributeDO::getId));
    }

    /**
     * 按模型编号查询主数据分发数量。
     */
    default Long selectCountByModelId(Long masterModelId) {
        return selectCount(MasterDistributeDO::getMasterModelId, masterModelId);
    }

}
