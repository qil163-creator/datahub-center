package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterPushCategoryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface MasterPushCategoryMapper extends BaseMapperX<MasterPushCategoryDO> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended('governance:master-push-category', 0))")
    String lockTree();

    default List<MasterPushCategoryDO> selectOrdered() {
        return selectList(new LambdaQueryWrapperX<MasterPushCategoryDO>()
                .orderByAsc(MasterPushCategoryDO::getSort, MasterPushCategoryDO::getId));
    }
}
