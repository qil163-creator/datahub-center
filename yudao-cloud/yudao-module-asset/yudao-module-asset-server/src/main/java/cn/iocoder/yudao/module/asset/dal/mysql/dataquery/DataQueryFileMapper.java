package cn.iocoder.yudao.module.asset.dal.mysql.dataquery;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataquery.DataQueryFileDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DataQueryFileMapper extends BaseMapperX<DataQueryFileDO> {

    /**
     * 按排序查询列表工单。
     */
    default List<DataQueryFileDO> selectListOrderBySort() {
        return selectList(new LambdaQueryWrapperX<DataQueryFileDO>()
                .orderByAsc(DataQueryFileDO::getNodeType)
                .orderByAsc(DataQueryFileDO::getSort)
                .orderByAsc(DataQueryFileDO::getId));
    }

    /**
     * 按父级编号与名称查询数据查询文件。
     */
    default DataQueryFileDO selectByParentIdAndName(Long parentId, String name) {
        return selectOne(DataQueryFileDO::getParentId, parentId, DataQueryFileDO::getName, name);
    }

    /**
     * 按父级编号查询数据查询文件数量。
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(DataQueryFileDO::getParentId, parentId);
    }

}
