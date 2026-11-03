package cn.iocoder.yudao.module.governance.dal.mysql.qualityrule;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDimensionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 稽查规则独立维度 Mapper。 */
@Mapper
public interface AuditRuleDimensionMapper extends BaseMapperX<AuditRuleDimensionDO> {

    /**
     * 查询维度列表。
     */
    default List<AuditRuleDimensionDO> selectDimensionList() {
        return selectList(new LambdaQueryWrapperX<AuditRuleDimensionDO>()
                .orderByAsc(AuditRuleDimensionDO::getSort)
                .orderByAsc(AuditRuleDimensionDO::getId));
    }
}
