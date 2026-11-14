package cn.iocoder.yudao.module.dataforge.dal.mysql.filltable;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableRuleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据填报-填报表校验规则 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillTableRuleMapper extends BaseMapperX<FillTableRuleDO> {

    /**
     * 按表编号查询规则（按排序、编号升序）。
     *
     * @param tableId 表编号
     * @return 规则列表
     */
    default List<FillTableRuleDO> selectListByTableId(Long tableId) {
        return selectList(new LambdaQueryWrapperX<FillTableRuleDO>()
                .eq(FillTableRuleDO::getTableId, tableId)
                .orderByAsc(FillTableRuleDO::getSort)
                .orderByAsc(FillTableRuleDO::getId));
    }

    /**
     * 按表编号删除规则。
     *
     * @param tableId 表编号
     */
    default void deleteByTableId(Long tableId) {
        delete(new LambdaQueryWrapperX<FillTableRuleDO>().eq(FillTableRuleDO::getTableId, tableId));
    }

}
