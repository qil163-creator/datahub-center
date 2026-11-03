package cn.iocoder.yudao.module.governance.dal.mysql.statictask;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 静态脱敏任务 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyStaticTaskMapper extends BaseMapperX<SafetyStaticTaskDO> {

    /**
     * 查询静态脱敏任务分页。
     */
    default PageResult<SafetyStaticTaskDO> selectPage(SafetyStaticTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyStaticTaskDO>()
                .likeIfPresent(SafetyStaticTaskDO::getTaskName, reqVO.getTaskName())
                .eqIfPresent(SafetyStaticTaskDO::getSourceDatabaseId, reqVO.getSourceDatabaseId())
                .eqIfPresent(SafetyStaticTaskDO::getTargetDatabaseId, reqVO.getTargetDatabaseId())
                .eqIfPresent(SafetyStaticTaskDO::getStatus, reqVO.getStatus())
                .orderByDesc(SafetyStaticTaskDO::getId));
    }

    /**
     * 按任务名称查询静态脱敏任务。
     */
    default SafetyStaticTaskDO selectByTaskName(String taskName) {
        return selectOne(SafetyStaticTaskDO::getTaskName, taskName);
    }

    /**
     * 查询上线且配置了 Cron 的定时任务（供调度 Job 扫描）
     */
    default List<SafetyStaticTaskDO> selectTimerOnlineList() {
        return selectList(new LambdaQueryWrapperX<SafetyStaticTaskDO>()
                .eq(SafetyStaticTaskDO::getStatus, SafetyStaticTaskDO.STATUS_ENABLE)
                .isNotNull(SafetyStaticTaskDO::getCronExpression)
                .ne(SafetyStaticTaskDO::getCronExpression, ""));
    }

    /**
     * 统计按规则编号。
     */
    default Long countByRuleId(Long ruleId) {
        if (ruleId == null) {
            return 0L;
        }
        return selectCount(new LambdaQueryWrapperX<SafetyStaticTaskDO>()
                .apply("jsonb_path_exists(COALESCE(field_rules_json::jsonb,'[]'::jsonb), "
                        + "'$.**.ruleId ? (@ == $ruleId || @ == $ruleIdText)'::jsonpath, "
                        + "jsonb_build_object('ruleId',CAST({0} AS numeric),'ruleIdText',CAST({0} AS text)))", ruleId));
    }

}
