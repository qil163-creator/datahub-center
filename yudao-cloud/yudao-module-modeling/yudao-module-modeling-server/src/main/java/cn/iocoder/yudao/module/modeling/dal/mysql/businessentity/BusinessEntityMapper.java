package cn.iocoder.yudao.module.modeling.dal.mysql.businessentity;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntityPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BusinessEntityMapper extends BaseMapperX<BusinessEntityDO> {

    /**
     * 按查询条件分页读取业务实体。
     *
     * @param request 请求参数
     * @return 按查询条件分页读取业务实体结果
     */
    default PageResult<BusinessEntityDO> selectPage(BusinessEntityPageReqVO request) {
        return selectPage(request, query(request));
    }

    /** 按同一筛选和排序读取树分页的候选实体，在组装包含关系后按根节点分页。 */
    default List<BusinessEntityDO> selectList(BusinessEntityPageReqVO request) {
        return selectList(query(request));
    }

    private LambdaQueryWrapper<BusinessEntityDO> query(BusinessEntityPageReqVO request) {
        return new LambdaQueryWrapperX<BusinessEntityDO>()
                .eqIfPresent(BusinessEntityDO::getDataDomainId, request.getDataDomainId())
                .eqIfPresent(BusinessEntityDO::getSubjectDomainId, request.getSubjectDomainId())
                .eqIfPresent(BusinessEntityDO::getEntityType, request.getEntityType())
                .eqIfPresent(BusinessEntityDO::getEntitySubtype, request.getEntitySubtype())
                .inIfPresent(BusinessEntityDO::getEntitySubtype, request.getEntitySubtypes())
                .eqIfPresent(BusinessEntityDO::getStatus, request.getStatus())
                .eqIfPresent(BusinessEntityDO::getLeaderUserId, request.getLeaderUserId())
                .and(request.getKeyword() != null && !request.getKeyword().isBlank(),
                        q -> q.like(BusinessEntityDO::getName, request.getKeyword())
                                .or().like(BusinessEntityDO::getCode, request.getKeyword()))
                .orderBy(true, "asc".equals(request.getSortOrder()), BusinessEntityDO::getCreateTime)
                .orderByDesc(BusinessEntityDO::getId);
    }

    /**
     * 按板块查询业务实体。
     *
     * @param boardId 板块编号
     * @return 按板块查询业务实体结果集合
     */
    default List<BusinessEntityDO> selectByBoard(Long boardId) {
        return selectList(new LambdaQueryWrapperX<BusinessEntityDO>()
                .eq(BusinessEntityDO::getDataDomainId, boardId)
                .orderByAsc(BusinessEntityDO::getName, BusinessEntityDO::getId));
    }

    /**
     * 查询业务实体的按编码。
     *
     * @param boardId 板块编号
     * @param code 编码
     * @return 业务实体
     */
    @Select("SELECT * FROM dh_modeling_business_entity WHERE deleted = 0 AND data_domain_id = #{boardId} AND lower(code) = lower(#{code})")
    BusinessEntityDO selectByCode(@Param("boardId") Long boardId, @Param("code") String code);
}
