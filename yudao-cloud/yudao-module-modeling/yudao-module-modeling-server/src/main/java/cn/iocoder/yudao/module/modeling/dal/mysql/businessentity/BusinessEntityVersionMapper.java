package cn.iocoder.yudao.module.modeling.dal.mysql.businessentity;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BusinessEntityVersionMapper extends BaseMapperX<BusinessEntityVersionDO> {

    /**
     * 按版本号倒序读取实体的全部版本。
     *
     * @param entityId 业务实体编号
     * @return 版本列表，最新版本在前
     */
    default List<BusinessEntityVersionDO> selectListByEntityId(Long entityId) {
        return selectList(new LambdaQueryWrapperX<BusinessEntityVersionDO>()
                .eq(BusinessEntityVersionDO::getEntityId, entityId)
                .orderByDesc(BusinessEntityVersionDO::getVersionNo));
    }

    /**
     * 读取实体的最新版本。
     *
     * @param entityId 业务实体编号
     * @return 最新版本，没有版本时为空
     */
    default BusinessEntityVersionDO selectLatest(Long entityId) {
        return selectOne(new LambdaQueryWrapperX<BusinessEntityVersionDO>()
                .eq(BusinessEntityVersionDO::getEntityId, entityId)
                .orderByDesc(BusinessEntityVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }

    /**
     * 逻辑删除实体的全部版本。
     *
     * @param entityId 业务实体编号
     */
    default void deleteByEntityId(Long entityId) {
        delete(BusinessEntityVersionDO::getEntityId, entityId);
    }
}
