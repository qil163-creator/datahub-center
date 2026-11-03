package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-元数据采集实例日志 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaCollectLogMapper extends BaseMapperX<MetaCollectLogDO> {

    /**
     * 按实例编号查询元数据采集日志列表。
     */
    default List<MetaCollectLogDO> selectListByInstanceId(Long instanceId) {
        return selectList(new LambdaQueryWrapperX<MetaCollectLogDO>()
                .eq(MetaCollectLogDO::getInstanceId, instanceId)
                .orderByAsc(MetaCollectLogDO::getLogTime)
                .orderByAsc(MetaCollectLogDO::getId).last("LIMIT 10000"));
    }

}
