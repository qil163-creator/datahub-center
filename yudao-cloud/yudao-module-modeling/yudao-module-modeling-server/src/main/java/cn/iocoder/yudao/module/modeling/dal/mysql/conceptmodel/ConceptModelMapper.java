package cn.iocoder.yudao.module.modeling.dal.mysql.conceptmodel;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo.ConceptModelPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.conceptmodel.ConceptModelDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConceptModelMapper extends BaseMapperX<ConceptModelDO> {

    /**
     * 按查询条件分页读取概念模型。
     *
     * @param request 请求参数
     * @return 按查询条件分页读取概念模型结果
     */
    default PageResult<ConceptModelDO> selectPage(ConceptModelPageReqVO request) {
        var query = new LambdaQueryWrapperX<ConceptModelDO>()
                .eqIfPresent(ConceptModelDO::getDataDomainId, request.getDataDomainId())
                .eqIfPresent(ConceptModelDO::getSubjectDomainId, request.getSubjectDomainId())
                .eqIfPresent(ConceptModelDO::getStatus, request.getStatus())
                .and(request.getKeyword() != null && !request.getKeyword().isBlank(),
                        nested -> nested.like(ConceptModelDO::getName, request.getKeyword())
                                .or().like(ConceptModelDO::getCode, request.getKeyword()))
                .orderByDesc(ConceptModelDO::getUpdateTime, ConceptModelDO::getId);
        // 列表不装载设计 JSON，避免分页批量返回大型设计正文。
        query.select(ConceptModelDO::getId, ConceptModelDO::getName, ConceptModelDO::getCode,
                ConceptModelDO::getDataDomainId, ConceptModelDO::getSubjectDomainId,
                ConceptModelDO::getDescription, ConceptModelDO::getStatus, ConceptModelDO::getRevision,
                ConceptModelDO::getCreateTime, ConceptModelDO::getUpdateTime);
        return selectPage(request, query);
    }

    /**
     * 更新概念模型的删除草稿。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @param updater 更新器
     * @return 符合条件或受影响的记录数量
     */
    @Update("""
            UPDATE dh_modeling_concept_model
            SET deleted = 1, revision = revision + 1, update_time = CURRENT_TIMESTAMP, updater = #{updater}
            WHERE id = #{id} AND revision = #{revision} AND status = 'DRAFT' AND deleted = 0
            """)
    int deleteDraft(@Param("id") Long id, @Param("revision") Long revision, @Param("updater") String updater);
}
