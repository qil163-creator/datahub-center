package cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeMappingDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StandardCodeMappingMapper extends BaseMapperX<StandardCodeMappingDO> {

    /**
     * 按所属人查询标准代码映射。
     */
    default List<StandardCodeMappingDO> selectByOwner(Integer id) {
        return selectList(new LambdaQueryWrapperX<StandardCodeMappingDO>().eq(StandardCodeMappingDO::getCodeSetId, id)
                .orderByAsc(StandardCodeMappingDO::getId));
    }
}
