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
     * 统计公式中引用该指标的复合指标（含草稿）。公式中的引用保存为规范编码，被引用指标为启用状态时编码不可修改。
     *
     * @param id 被引用指标编号
     * @return 引用它的复合指标数量
     */
    @Select("SELECT count(*) FROM dh_modeling_indicator_model c JOIN dh_modeling_indicator_model t ON t.id = #{id} "
            + "AND t.deleted = 0 WHERE c.deleted = 0 AND c.indicator_type = 'COMPOSITE' AND c.id <> t.id "
            + "AND strpos(c.formula, '{' || t.code || '}') > 0")
    Long countCompositeReferences(@Param("id") Long id);

    /**
     * 批量统计复合指标引用数量。
     *
     * @param ids 被引用指标编号
     * @return 引用数量
     */
    @Select("<script>SELECT t.id AS baseIndicatorId, count(c.id) AS total FROM dh_modeling_indicator_model t "
            + "JOIN dh_modeling_indicator_model c ON c.deleted = 0 AND c.indicator_type = 'COMPOSITE' AND c.id &lt;&gt; t.id "
            + "AND strpos(c.formula, '{' || t.code || '}') &gt; 0 WHERE t.deleted = 0 AND t.id IN "
            + "<foreach collection='list' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY t.id</script>")
    List<ReferenceCount> selectCompositeReferenceCounts(List<Long> ids);

    /**
     * 按编码忽略大小写批量查询。
     *
     * @param codes 编码
     * @return 指标
     */
    default List<IndicatorModelDO> selectListByCodesIgnoreCase(java.util.Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        var lowered = codes.stream().map(code -> code.toLowerCase(java.util.Locale.ROOT)).distinct().toList();
        var query = new LambdaQueryWrapperX<IndicatorModelDO>();
        StringBuilder in = new StringBuilder("lower(code) IN (");
        for (int index = 0; index < lowered.size(); index++) {
            in.append(index == 0 ? "" : ",").append("{").append(index).append("}");
        }
        query.apply(in.append(")").toString(), lowered.toArray());
        return selectList(query);
    }

    /**
     * 启用指标轻量选项。
     *
     * @param dataDomainId 数据域，可为空
     * @return 启用指标
     */
    default List<IndicatorModelDO> selectActiveOptions(Long dataDomainId) {
        return selectList(new LambdaQueryWrapperX<IndicatorModelDO>()
                .eq(IndicatorModelDO::getStatus, IndicatorModelDO.ACTIVE)
                .eqIfPresent(IndicatorModelDO::getDataDomainId, dataDomainId)
                .orderByAsc(IndicatorModelDO::getIndicatorType).orderByAsc(IndicatorModelDO::getName)
                .last("LIMIT 2000"));
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

