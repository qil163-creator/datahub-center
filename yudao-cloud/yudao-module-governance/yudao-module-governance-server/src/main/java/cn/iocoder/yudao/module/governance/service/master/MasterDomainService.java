package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;

import java.util.List;

/** 主数据域在平台内共享，不接受客户端的组织标识作为授权依据。 */
public interface MasterDomainService {

    /**
     * 创建域。
     */
    Long createDomain(MasterDomainSaveReqVO req);

    /**
     * 更新域。
     */
    void updateDomain(MasterDomainSaveReqVO req);

    /**
     * 更新主数据域的状态。
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除域。
     */
    void deleteDomain(Long id);

    /**
     * 删除域列表。
     */
    void deleteDomainList(List<Long> ids);

    /**
     * 读取域。
     */
    MasterDomainRespVO getDomain(Long id);

    /**
     * 读取域分页。
     */
    PageResult<MasterDomainRespVO> getDomainPage(MasterDomainPageReqVO req);

    /**
     * 读取简要列表。
     */
    List<MasterDomainRespVO> getSimpleList();
}
