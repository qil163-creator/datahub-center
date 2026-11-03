package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeStateEnum;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 在线填报-工作项填报人 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillAssigneeMapper extends BaseMapperX<FillAssigneeDO> {

    /**
     * 按工作项编号查询有效列表。
     *
     * @param workItemId 工作项编号
     * @return 按工作项编号查询有效列表结果集合
     */
    default List<FillAssigneeDO> selectValidListByWorkItemId(Long workItemId) {
        return selectList(new LambdaQueryWrapperX<FillAssigneeDO>()
                .eq(FillAssigneeDO::getWorkItemId, workItemId)
                .eq(FillAssigneeDO::getState, FillAssigneeStateEnum.VALID.getState()));
    }

    /**
     * 按工作项编号与用户编号查询填报填报人。
     *
     * @param workItemId 工作项编号
     * @param userId 用户编号
     * @return 填报填报人
     */
    default FillAssigneeDO selectByWorkItemIdAndUserId(Long workItemId, Long userId) {
        return selectOne(new LambdaQueryWrapperX<FillAssigneeDO>()
                .eq(FillAssigneeDO::getWorkItemId, workItemId)
                .eq(FillAssigneeDO::getUserId, userId));
    }

    /** "我的填报"：当前用户的有效填报授权 */
    default List<FillAssigneeDO> selectValidListByUserId(Long userId) {
        return selectList(new LambdaQueryWrapperX<FillAssigneeDO>()
                .eq(FillAssigneeDO::getUserId, userId)
                .eq(FillAssigneeDO::getState, FillAssigneeStateEnum.VALID.getState()));
    }

    /**
     * 按工作项编号查询有效数量。
     *
     * @param workItemId 工作项编号
     * @return SELECT有效数量按工作项编号数值
     */
    default long selectValidCountByWorkItemId(Long workItemId) {
        return selectCount(new LambdaQueryWrapperX<FillAssigneeDO>()
                .eq(FillAssigneeDO::getWorkItemId, workItemId)
                .eq(FillAssigneeDO::getState, FillAssigneeStateEnum.VALID.getState()));
    }

    /**
     * 按工作项编号集合查询有效列表。
     *
     * @param workItemIds 工作项编号集合
     * @return 按工作项编号集合查询有效列表结果集合
     */
    default List<FillAssigneeDO> selectValidListByWorkItemIds(Collection<Long> workItemIds) {
        return selectList(new LambdaQueryWrapperX<FillAssigneeDO>()
                .in(FillAssigneeDO::getWorkItemId, workItemIds)
                .eq(FillAssigneeDO::getState, FillAssigneeStateEnum.VALID.getState()));
    }

}
