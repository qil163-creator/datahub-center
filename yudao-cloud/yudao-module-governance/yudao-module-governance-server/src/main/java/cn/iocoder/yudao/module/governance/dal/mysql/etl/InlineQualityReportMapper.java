package cn.iocoder.yudao.module.governance.dal.mysql.etl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.etl.InlineQualityReportDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InlineQualityReportMapper extends BaseMapperX<InlineQualityReportDO> {

    /**
     * 按指定条件读取并锁定内联质量报告记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_etl_quality_report WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    InlineQualityReportDO selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按幂等性键查询内联质量报告。
     */
    default InlineQualityReportDO selectByIdempotencyKey(String key) {
        return selectOne(new LambdaQueryWrapperX<InlineQualityReportDO>()
                .eq(InlineQualityReportDO::getIdempotencyKey, key));
    }
}
