package cn.iocoder.yudao.module.modeling.dal.mysql.businesscondition;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo.BusinessConditionPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition.BusinessConditionDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BusinessConditionMapper extends BaseMapperX<BusinessConditionDO> {

    /**
     * 锁定并读取业务限定记录。
     *
     * @return 处理是否成功
     */
    @Select("SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended('modeling-ownership-global', 0))) guard")
    Boolean lockReferences();

    /**
     * 按编码忽略大小写查询业务限定。
     *
     * @param code 编码
     * @return 业务限定
     */
    default BusinessConditionDO selectByCodeIgnoreCase(String code) {
        return selectOne(new LambdaQueryWrapperX<BusinessConditionDO>().apply("lower(code) = lower({0})", code));
    }

    /**
     * 锁定并读取业务限定当前记录。
     *
     * @param id 记录编号
     * @return 业务限定
     */
    default BusinessConditionDO selectForUpdate(Long id) {
        return selectOne(
                new LambdaQueryWrapperX<BusinessConditionDO>().eq(BusinessConditionDO::getId, id).last("FOR UPDATE"));
    }

    /**
     * 按查询条件分页读取业务限定。
     *
     * @param request 请求参数
     * @return 按查询条件分页读取业务限定结果
     */
    default PageResult<BusinessConditionDO> selectPage(BusinessConditionPageReqVO request) {
        var query = new LambdaQueryWrapperX<BusinessConditionDO>()
                .eqIfPresent(BusinessConditionDO::getStatus, request.getStatus())
                .eqIfPresent(BusinessConditionDO::getDataDomainId, request.getDataDomainId())
                .eqIfPresent(BusinessConditionDO::getSubjectDomainId, request.getSubjectDomainId())
                .eqIfPresent(BusinessConditionDO::getSourceModelId, request.getSourceModelId())
                .eqIfPresent(BusinessConditionDO::getConfigType, request.getConfigType())
                .eqIfPresent(BusinessConditionDO::getDataFreshness, request.getDataFreshness());
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            query.and(item -> item.like(BusinessConditionDO::getName, request.getKeyword())
                    .or().like(BusinessConditionDO::getCode, request.getKeyword()));
        }
        return selectPage(request, query.orderByDesc(BusinessConditionDO::getId));
    }
}
