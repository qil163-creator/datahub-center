package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.*;

import java.util.List;

public interface BusinessEntityService {

    /**
     * 新增业务实体。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    Long create(BusinessEntitySaveReqVO request);

    /**
     * 修改业务实体及关系。
     *
     * @param request 请求参数
     */
    void update(BusinessEntityUpdateReqVO request);

    /**
     * 上线或下线业务实体。
     *
     * @param request 请求参数
     */
    void updateStatus(BusinessEntityStatusReqVO request);

    /**
     * 原子批量上线或下线业务实体。
     *
     * @param request 请求参数
     */
    void updateStatusBatch(BusinessEntityBatchReqVO request);

    /**
     * 删除业务实体。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    void delete(Long id, Long expectedRevision);

    /**
     * 原子批量删除业务实体。
     *
     * @param request 请求参数
     */
    void deleteBatch(BusinessEntityBatchReqVO request);

    /**
     * 获得业务实体及其关系。
     *
     * @param id 记录编号
     * @return 业务实体响应数据
     */
    BusinessEntityRespVO get(Long id);

    /**
     * 获得业务实体分页。
     *
     * @param request 请求参数
     * @return 业务实体分页结果
     */
    PageResult<BusinessEntityRespVO> page(BusinessEntityPageReqVO request);

    /**
     * 获得按流程包含关系分组的实体分页；先筛选实体，再按根节点分页。
     * 匹配的事件仅在其所属流程也匹配时嵌套，否则作为根节点返回。
     *
     * @param request 请求参数
     * @return 根节点分页及匹配的流程事件
     */
    PageResult<BusinessEntityRespVO> treePage(BusinessEntityPageReqVO request);

    /**
     * 获得板块实体及流程占用约束。
     *
     * @param dataDomainId 数据域编号
     * @param excludeId 排除编号
     * @return 业务实体选项响应数据
     */
    BusinessEntityOptionsRespVO options(Long dataDomainId, Long excludeId);
}
