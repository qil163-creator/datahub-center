package cn.iocoder.yudao.module.dataforge.dal.mysql.filltable;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据填报-填报表字段 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillTableColumnMapper extends BaseMapperX<FillTableColumnDO> {

    /**
     * 按表编号查询填报表列列表。
     *
     * @param tableId 表编号
     * @return 按表编号查询填报表列列表结果集合
     */
    default List<FillTableColumnDO> selectListByTableId(Long tableId) {
        return selectList(new LambdaQueryWrapperX<FillTableColumnDO>()
                .eq(FillTableColumnDO::getTableId, tableId)
                .orderByAsc(FillTableColumnDO::getSort)
                .orderByAsc(FillTableColumnDO::getId));
    }

    /**
     * 按表编号与名称查询填报表列。
     *
     * @param tableId 表编号
     * @param name 名称
     * @return 填报表列
     */
    default FillTableColumnDO selectByTableIdAndName(Long tableId, String name) {
        return selectOne(new LambdaQueryWrapperX<FillTableColumnDO>()
                .eq(FillTableColumnDO::getTableId, tableId)
                .eq(FillTableColumnDO::getName, name));
    }

    /**
     * 按表编号删除填报表列。
     *
     * @param tableId 表编号
     */
    default void deleteByTableId(Long tableId) {
        delete(new LambdaQueryWrapperX<FillTableColumnDO>()
                .eq(FillTableColumnDO::getTableId, tableId));
    }

}
