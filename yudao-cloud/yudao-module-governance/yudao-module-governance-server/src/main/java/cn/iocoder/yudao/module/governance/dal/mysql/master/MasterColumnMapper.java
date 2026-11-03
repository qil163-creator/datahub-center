package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 主数据模型字段 Mapper
 *
 * @author Li
 */
@Mapper
public interface MasterColumnMapper extends BaseMapperX<MasterColumnDO> {

    /**
     * 按模型编号查询主数据字段列表。
     */
    default List<MasterColumnDO> selectListByModelId(Long masterModelId) {
        return selectList(new LambdaQueryWrapperX<MasterColumnDO>()
                .eq(MasterColumnDO::getMasterModelId, masterModelId)
                .orderByAsc(MasterColumnDO::getSort)
                .orderByAsc(MasterColumnDO::getId));
    }

    /**
     * 按模型编号删除主数据字段记录。
     */
    default void deleteByModelId(Long masterModelId) {
        delete(MasterColumnDO::getMasterModelId, masterModelId);
    }

}
