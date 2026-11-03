package cn.iocoder.yudao.module.dataforge.dal.mysql.integration;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationCategoryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntegrationCategoryMapper extends BaseMapperX<IntegrationCategoryDO> {

    /** 平台类目树写锁，必须在经过 Spring 代理的真实事务中调用。 */
    @Select("SELECT true FROM (SELECT pg_advisory_xact_lock(215030)) tree_lock")
    boolean lockTree();
}
