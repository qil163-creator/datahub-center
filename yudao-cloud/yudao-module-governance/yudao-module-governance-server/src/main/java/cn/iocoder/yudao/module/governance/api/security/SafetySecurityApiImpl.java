package cn.iocoder.yudao.module.governance.api.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyMaskExemptRespDTO;
import cn.iocoder.yudao.module.governance.service.accesslog.SafetyAccessLogService;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyWhitelistService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据安全 RPC 接口实现
 *
 * @author Li
 */
@RestController
@Validated
public class SafetySecurityApiImpl implements SafetySecurityApi {

    @Resource
    private SafetyAccessLogService accessLogService;

    @Resource
    private SafetyWhitelistService whitelistService;

    /**
     * 记录敏感数据访问审计日志。
     */
    @Override
    public CommonResult<Boolean> recordAccessLog(SafetyAccessLogCreateReqDTO reqDTO) {
        accessLogService.recordAccessLog(reqDTO);
        return success(true);
    }

    /**
     * 查询用户是否命中脱敏白名单（已审批 + 启用 + 有效期内）。
     */
    @Override
    public CommonResult<SafetyMaskExemptRespDTO> getMaskExempt(Long userId, String scene) {
        return success(whitelistService.getMaskExempt(userId, scene));
    }

}
