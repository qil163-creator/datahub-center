package cn.iocoder.yudao.module.governance.service.standardterm;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据标准--业务术语 Service 接口
 *
 * @author Li
 */
public interface StandardTermService {

    /**
     * 创建业务术语
     */
    Long createStandardTerm(@Valid StandardTermSaveReqVO createReqVO);

    /**
     * 更新业务术语
     */
    void updateStandardTerm(@Valid StandardTermSaveReqVO updateReqVO);

    /**
     * 删除业务术语
     */
    void deleteStandardTerm(Long id);

    /**
     * 批量删除业务术语
     */
    void deleteStandardTermListByIds(List<Long> ids);

    /**
     * 获得业务术语
     */
    StandardTermDO getStandardTerm(Long id);

    /**
     * 检索平台内启用的当前术语，最多 50 条，按名称或别名匹配。
     */
    List<StandardTermDO> getEnabledStandardTermList(String keyword);

    /**
     * 批量读取平台内当前术语，保留已停用项，逻辑删除项不可见。
     */
    List<StandardTermDO> getStandardTermList(List<Long> ids);

    /**
     * 获得业务术语分页
     */
    PageResult<StandardTermDO> getStandardTermPage(StandardTermPageReqVO pageReqVO);

}
