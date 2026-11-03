package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-模板版本 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillTableVersionMapper extends BaseMapperX<FillTableVersionDO> {

    /**
     * 按表编号查询填报表版本列表。
     *
     * @param tableId 表编号
     * @return 按表编号查询填报表版本列表结果集合
     */
    default List<FillTableVersionDO> selectListByTableId(Long tableId) {
        return selectList(new LambdaQueryWrapperX<FillTableVersionDO>()
                .eq(FillTableVersionDO::getTableId, tableId)
                .orderByDesc(FillTableVersionDO::getVersionNo));
    }

    /**
     * 按表编号查询最新。
     *
     * @param tableId 表编号
     * @return 填报表版本
     */
    default FillTableVersionDO selectLatestByTableId(Long tableId) {
        return selectOne(new LambdaQueryWrapperX<FillTableVersionDO>()
                .eq(FillTableVersionDO::getTableId, tableId)
                .orderByDesc(FillTableVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }

}
