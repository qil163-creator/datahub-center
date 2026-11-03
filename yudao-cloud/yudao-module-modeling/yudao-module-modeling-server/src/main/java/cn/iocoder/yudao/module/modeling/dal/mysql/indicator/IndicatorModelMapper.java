package cn.iocoder.yudao.module.modeling.dal.mysql.indicator;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorModelPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IndicatorModelMapper extends BaseMapperX<IndicatorModelDO> {

    /**
     * 锁定并读取指标模型记录。
     *
     * @return 处理是否成功
     */
    @Select("SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended('modeling-ownership-global', 0))) guard")
    Boolean lockReferences();

    /**
     * 按编码忽略大小写查询指标模型。
     *
     * @param code 编码
     * @return 指标模型
     */
    default IndicatorModelDO selectByCodeIgnoreCase(String code) {
        return selectOne(new LambdaQueryWrapperX<IndicatorModelDO>().apply("lower(code) = lower({0})", code));
    }

    /**
     * 按查询条件分页读取指标模型。
     *
     * @param request 请求参数
     * @return 按查询条件分页读取指标模型结果
     */
    default PageResult<IndicatorModelDO> selectPage(IndicatorModelPageReqVO request) {
        var query = new LambdaQueryWrapperX<IndicatorModelDO>()
                .eqIfPresent(IndicatorModelDO::getIndicatorType, request.getIndicatorType())
                .eqIfPresent(IndicatorModelDO::getStatus, request.getStatus())
                .eqIfPresent(IndicatorModelDO::getDataDomainId, request.getDataDomainId())
                .eqIfPresent(IndicatorModelDO::getBaseIndicatorId, request.getBaseIndicatorId())
                .eqIfPresent(IndicatorModelDO::getSubjectDomainId, request.getSubjectDomainId());
        if (request.getSourceModelId() != null) {
            query.apply("(source_model_id = {0} OR base_indicator_id IN "
                            + "(SELECT id FROM dh_modeling_indicator_model WHERE deleted=0 AND source_model_id={0}))",
                    request.getSourceModelId());
        }
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            query.and(item -> item.like(IndicatorModelDO::getCode, request.getKeyword())
                    .or().like(IndicatorModelDO::getName, request.getKeyword()));
        }
        return selectPage(request, query.orderByDesc(IndicatorModelDO::getId));
    }

    /**
     * 锁定并读取指标模型当前记录。
     *
     * @param id 记录编号
     * @return 指标模型
     */
    default IndicatorModelDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<IndicatorModelDO>()
                .eq(IndicatorModelDO::getId, id).last("FOR UPDATE"));
    }

    /**
     * 统计引用。
     *
     * @param id 记录编号
     * @return 符合条件或受影响的记录数量
     */
    default Long countReferences(Long id) {
        return selectCount(IndicatorModelDO::getBaseIndicatorId, id);
    }

    /**
     * 查询指标模型的派生引用数量。
     *
     * @param ids 记录编号集合
     * @return 指标模型的派生引用数量结果集合
     */
    @Select("<script>SELECT base_indicator_id AS baseIndicatorId, count(*) AS total "
            + "FROM dh_modeling_indicator_model WHERE deleted=0 AND base_indicator_id IN "
            + "<foreach collection='list' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY base_indicator_id</script>")
    List<ReferenceCount> selectDerivedReferenceCounts(List<Long> ids);

    @lombok.Data
    class ReferenceCount {

        private Long baseIndicatorId;

        private Long total;
    }

    /**
     * 统计来源模型引用。
     *
     * @param id 记录编号
     * @return 符合条件或受影响的记录数量
     */
    @Select("SELECT (SELECT count(*) FROM dh_modeling_indicator_model WHERE deleted=0 AND source_model_id=#{id})"
            + " + (SELECT count(*) FROM dh_modeling_business_condition WHERE deleted=0 AND source_model_id=#{id})")
    Long countSourceModelReferences(@Param("id") Long id);

    /**
     * 查询原子选项。
     *
     * @return 原子选项结果集合
     */
    default List<IndicatorModelDO> selectAtomicOptions() {
        return selectList(new LambdaQueryWrapperX<IndicatorModelDO>()
                .eq(IndicatorModelDO::getIndicatorType, IndicatorModelDO.ATOMIC)
                .eq(IndicatorModelDO::getStatus, IndicatorModelDO.ACTIVE)
                .orderByAsc(IndicatorModelDO::getName));
    }
}

