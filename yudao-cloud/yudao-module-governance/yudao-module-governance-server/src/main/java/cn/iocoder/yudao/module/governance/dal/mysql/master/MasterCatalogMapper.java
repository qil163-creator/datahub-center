package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterCatalogDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 主数据目录 Mapper
 *
 * @author Li
 */
@Mapper
public interface MasterCatalogMapper extends BaseMapperX<MasterCatalogDO> {

    /**
     * 按无查询列表工单。
     */
    default List<MasterCatalogDO> selectListOrderByNo() {
        return selectList(new LambdaQueryWrapperX<MasterCatalogDO>()
                .orderByAsc(MasterCatalogDO::getOrderNo)
                .orderByAsc(MasterCatalogDO::getId));
    }

    /**
     * 按父级编号查询主数据目录数量。
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(MasterCatalogDO::getParentId, parentId);
    }

    /**
     * 按父级编号与名称查询主数据目录。
     */
    default MasterCatalogDO selectByParentIdAndName(Long parentId, String name) {
        return selectOne(new LambdaQueryWrapperX<MasterCatalogDO>()
                .eq(MasterCatalogDO::getParentId, parentId)
                .eq(MasterCatalogDO::getName, name)
                .last("LIMIT 1"));
    }

}
