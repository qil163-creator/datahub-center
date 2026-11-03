package cn.iocoder.yudao.module.modeling.service.businesscondition;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo.*;

public interface BusinessConditionService {

    /**
     * 创建业务限定定义。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    Long create(BusinessConditionSaveReqVO request);

    /**
     * 修改未启用的业务限定。
     *
     * @param request 请求参数
     */
    void update(BusinessConditionSaveReqVO request);

    /**
     * 删除未启用的业务限定。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    void delete(Long id, Long expectedRevision);

    /**
     * 启用或停用业务限定。
     *
     * @param request 请求参数
     */
    void updateStatus(BusinessConditionStatusReqVO request);

    /**
     * 获得业务限定详情。
     *
     * @param id 记录编号
     * @return 业务限定响应数据
     */
    BusinessConditionRespVO get(Long id);

    /**
     * 分页查询业务限定。
     *
     * @param request 请求参数
     * @return 分页查询业务限定结果
     */
    PageResult<BusinessConditionRespVO> page(BusinessConditionPageReqVO request);
}
