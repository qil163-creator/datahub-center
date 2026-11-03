package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainPageReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;

import java.util.List;

/**
 * 数据分域 Service 接口
 *
 * @author Li
 */
public interface DataDomainService {

    /**
     * 创建数据分域
     *
     * @return 编号
     */
    Long createDataDomain(DataDomainSaveReqVO createReqVO);

    /**
     * 更新数据分域
     */
    void updateDataDomain(DataDomainSaveReqVO updateReqVO);

    /**
     * 更新数据分域状态
     */
    void updateDataDomainStatus(Long id, Integer status);

    /**
     * 更新板块六类逻辑表命名规则。
     *
     * @param request 请求参数
     */
    void updateNamingRules(
            cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainNamingRulesReqVO request);

    /**
     * 删除数据分域（被统一模型或分析主题引用时不可删除）
     */
    void deleteDataDomain(Long id);

    /** 在同一事务内删除所有选中板块，任一引用冲突则全部回滚。 */
    void deleteDataDomains(List<Long> ids);

    /**
     * 获得数据分域
     */
    DataDomainDO getDataDomain(Long id);

    /**
     * 获得数据分域分页
     */
    PageResult<DataDomainDO> getDataDomainPage(DataDomainPageReqVO pageReqVO);

    /**
     * 获得数据分域全量列表（下拉选择用）
     */
    List<DataDomainDO> getDataDomainSimpleList();

}
