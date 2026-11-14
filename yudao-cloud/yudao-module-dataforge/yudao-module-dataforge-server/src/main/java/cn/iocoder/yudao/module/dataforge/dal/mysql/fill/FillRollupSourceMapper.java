package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillRollupSourceDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-汇总来源 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillRollupSourceMapper extends BaseMapperX<FillRollupSourceDO> {

    default List<FillRollupSourceDO> selectListByWorkItemId(Long workItemId) {
        return selectList(new LambdaQueryWrapperX<FillRollupSourceDO>()
                .eq(FillRollupSourceDO::getWorkItemId, workItemId));
    }

    default int deleteByWorkItemId(Long workItemId) {
        return delete(new LambdaQueryWrapperX<FillRollupSourceDO>()
                .eq(FillRollupSourceDO::getWorkItemId, workItemId));
    }

}
