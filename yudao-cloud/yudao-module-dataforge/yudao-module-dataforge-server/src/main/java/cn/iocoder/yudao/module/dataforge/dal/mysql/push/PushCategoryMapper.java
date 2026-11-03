package cn.iocoder.yudao.module.dataforge.dal.mysql.push;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.PushCategoryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface PushCategoryMapper extends BaseMapperX<PushCategoryDO> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended('dataforge:push-category', 0))")
    String lockTree();

    default List<PushCategoryDO> selectOrdered() {
        return selectList(new LambdaQueryWrapperX<PushCategoryDO>()
                .orderByAsc(PushCategoryDO::getSort, PushCategoryDO::getId));
    }
}
