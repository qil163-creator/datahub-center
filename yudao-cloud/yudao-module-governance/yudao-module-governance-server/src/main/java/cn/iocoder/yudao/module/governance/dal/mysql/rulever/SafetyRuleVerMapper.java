package cn.iocoder.yudao.module.governance.dal.mysql.rulever;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.rulever.SafetyRuleVerDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据安全--脱敏规则版本 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyRuleVerMapper extends BaseMapperX<SafetyRuleVerDO> {

    /** 摘要使用 PostgreSQL 实际持久化表示，避免 JSONB 重排造成错误的篡改判断。 */
    @Select("SELECT CAST(CAST(#{config} AS jsonb) AS text)")
    String normalizeConfig(@Param("config") String config);

    /**
     * 按规则编号查询脱敏规则版本列表。
     */
    default List<SafetyRuleVerDO> selectListByRuleId(Long ruleId) {
        return selectList(new LambdaQueryWrapperX<SafetyRuleVerDO>()
                .eq(SafetyRuleVerDO::getRuleId, ruleId)
                .orderByDesc(SafetyRuleVerDO::getVersionNo));
    }

    /**
     * 按规则编号查询最新。
     */
    default SafetyRuleVerDO selectLatestByRuleId(Long ruleId) {
        return selectOne(new LambdaQueryWrapperX<SafetyRuleVerDO>()
                .eq(SafetyRuleVerDO::getRuleId, ruleId)
                .orderByDesc(SafetyRuleVerDO::getVersionNo)
                .last("LIMIT 1"));
    }

}
