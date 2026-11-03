package cn.iocoder.yudao.module.supply.dal.mysql.dataapi;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiReleaseDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DataApiReleaseMapper extends BaseMapperX<DataApiReleaseDO> {

    /**
     * 按API 编号查询数据 API发布列表。
     *
     * @param apiId API 编号
     * @return 按API 编号查询数据 API发布列表结果集合
     */
    default List<DataApiReleaseDO> selectListByApiId(Long apiId) {
        return selectList(new LambdaQueryWrapperX<DataApiReleaseDO>()
                .eq(DataApiReleaseDO::getApiId, apiId)
                .orderByDesc(DataApiReleaseDO::getRevision));
    }

    /**
     * 查询最大值修订号。
     *
     * @param apiId API 编号
     * @return SELECT最大值修订号数值
     */
    default Integer selectMaxRevision(Long apiId) {
        DataApiReleaseDO latest = selectOne(new LambdaQueryWrapperX<DataApiReleaseDO>()
                .eq(DataApiReleaseDO::getApiId, apiId)
                .orderByDesc(DataApiReleaseDO::getRevision)
                .last("LIMIT 1"));
        return latest == null || latest.getRevision() == null ? 0 : latest.getRevision();
    }
}
