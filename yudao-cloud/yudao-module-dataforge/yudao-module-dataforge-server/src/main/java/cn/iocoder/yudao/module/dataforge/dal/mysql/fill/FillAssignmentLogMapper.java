package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssignmentLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-填报人变更日志 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillAssignmentLogMapper extends BaseMapperX<FillAssignmentLogDO> {

    /**
     * 按工作项编号查询填报分配日志列表。
     *
     * @param workItemId 工作项编号
     * @return 按工作项编号查询填报分配日志列表结果集合
     */
    default List<FillAssignmentLogDO> selectListByWorkItemId(Long workItemId) {
        return selectList(new LambdaQueryWrapperX<FillAssignmentLogDO>()
                .eq(FillAssignmentLogDO::getWorkItemId, workItemId)
                .orderByDesc(FillAssignmentLogDO::getId));
    }

}
