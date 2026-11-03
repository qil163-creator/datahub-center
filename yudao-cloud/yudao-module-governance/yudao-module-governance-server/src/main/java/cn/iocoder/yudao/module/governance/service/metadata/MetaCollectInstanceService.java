package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectInstancePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectLogDO;

import java.util.List;

/**
 * 数据治理-元数据采集实例 Service
 *
 * @author Li
 */
public interface MetaCollectInstanceService {

    /**
     * 采集实例分页
     */
    PageResult<MetaCollectInstanceDO> getInstancePage(MetaCollectInstancePageReqVO pageReqVO);

    /**
     * 获取采集实例
     */
    MetaCollectInstanceDO getInstance(Long id);

    /**
     * 获取采集实例日志（按时间升序）
     */
    List<MetaCollectLogDO> getInstanceLogList(Long instanceId);

    /**
     * 删除实例。
     */
    void deleteInstances(java.util.List<Long> ids);

    /**
     * 向指定元数据采集实例追加运行日志。
     */
    void writeLog(Long id, java.io.OutputStream output) throws java.io.IOException;
}
