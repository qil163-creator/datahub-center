package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTargetScopeRegistryDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-目标表写入登记 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillTargetScopeRegistryMapper extends BaseMapperX<FillTargetScopeRegistryDO> {

    /** 计划发布时冲突检测：查询同一目标表的占用中登记 */
    default List<FillTargetScopeRegistryDO> selectActiveListByTarget(Long targetDatabaseId, String targetTableName) {
        return selectList(new LambdaQueryWrapperX<FillTargetScopeRegistryDO>()
                .eq(FillTargetScopeRegistryDO::getTargetDatabaseId, targetDatabaseId)
                .eq(FillTargetScopeRegistryDO::getTargetTableName, targetTableName)
                .eq(FillTargetScopeRegistryDO::getStatus, 1));
    }

    /**
     * 按目标与计划查询填报目标范围注册表。
     *
     * @param targetDatabaseId 目标数据源编号
     * @param targetTableName 目标表名
     * @param planId 计划编号
     * @return 填报目标范围注册表
     */
    default FillTargetScopeRegistryDO selectByTargetAndPlan(Long targetDatabaseId, String targetTableName,
            Long planId) {
        return selectOne(new LambdaQueryWrapperX<FillTargetScopeRegistryDO>()
                .eq(FillTargetScopeRegistryDO::getTargetDatabaseId, targetDatabaseId)
                .eq(FillTargetScopeRegistryDO::getTargetTableName, targetTableName)
                .eq(FillTargetScopeRegistryDO::getPlanId, planId));
    }

    /**
     * 按计划编号查询填报目标范围注册表列表。
     *
     * @param planId 计划编号
     * @return 按计划编号查询填报目标范围注册表列表结果集合
     */
    default List<FillTargetScopeRegistryDO> selectListByPlanId(Long planId) {
        return selectList(FillTargetScopeRegistryDO::getPlanId, planId);
    }

}
