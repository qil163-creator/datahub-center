package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlToolDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 可视化 ETL 工具箱 Mapper
 *
 * @author Li
 */
@Mapper
public interface EtlToolMapper extends BaseMapperX<EtlToolDO> {

    /**
     * 查询启用状态列表。
     *
     * @return 启用状态列表结果集合
     */
    default List<EtlToolDO> selectEnabledList() {
        return selectList(new LambdaQueryWrapperX<EtlToolDO>()
                .eq(EtlToolDO::getEnabled, 1)
                .orderByAsc(EtlToolDO::getOrderNo)
                .orderByAsc(EtlToolDO::getId));
    }

}
