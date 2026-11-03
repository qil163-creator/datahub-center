package cn.iocoder.yudao.module.governance.service.accesslog;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import cn.iocoder.yudao.module.governance.controller.admin.accesslog.vo.SafetyAccessLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.accesslog.SafetyAccessLogDO;

/**
 * 敏感数据访问审计 Service 接口
 *
 * @author Li
 */
public interface SafetyAccessLogService {

    /**
     * 记录一条访问审计日志（异常自吞，不影响主流程）
     */
    void recordAccessLog(SafetyAccessLogCreateReqDTO reqDTO);

    /**
     * 获得访问审计分页。
     */
    PageResult<SafetyAccessLogDO> getAccessLogPage(SafetyAccessLogPageReqVO pageReqVO);

}
