package cn.iocoder.yudao.module.modeling.dal.mysql.model;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.SourceSystemDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SourceSystemMapper extends BaseMapperX<SourceSystemDO> {

    /**
     * 查询启用状态列表。
     *
     * @return 启用状态列表结果集合
     */
    default List<SourceSystemDO> selectEnabledList() {
        return selectList(new LambdaQueryWrapperX<SourceSystemDO>()
                .eq(SourceSystemDO::getStatus, 0)
                .orderByAsc(SourceSystemDO::getSort)
                .orderByAsc(SourceSystemDO::getId));
    }

}
