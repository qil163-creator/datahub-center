package cn.iocoder.yudao.module.asset.dal.mysql.monitor;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AssetUsageLogMapper extends BaseMapperX<AssetUsageLogDO> {

}
