package cn.iocoder.yudao.module.modeling.api.registration;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.api.registration.dto.*;
import cn.iocoder.yudao.module.modeling.service.registration.RegistrationContextService;
import jakarta.annotation.Resource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
public class RegistrationContextApiImpl implements RegistrationContextApi {

    @Resource
    private RegistrationContextService service;

    @Resource
    private SecurityFrameworkService security;

    /**
     * 校验登记权限。
     */
    private void requireRegistrationPermission() {
        if (!security.hasAnyPermissions("asset:data-asset:create", "asset:data-asset:update")) {
            throw new AccessDeniedException("无资产注册权限");
        }
    }

    /**
     * 读取全平台已启用的业务选项白名单。
     *
     * @param board 板块
     * @param type 类型标识
     * @param subject 主体
     * @return 登记上下文响应数据
     */
    @Override
    public CommonResult<RegistrationContextRespDTO> context(Long board, String type, Long subject) {
        requireRegistrationPermission();
        return success(service.context(board, type, subject));
    }

    /**
     * 按真实业务选择和持久命名规则生成推荐名称。
     *
     * @param request 请求参数
     * @return 登记名称响应数据
     */
    @Override
    public CommonResult<RegistrationNameRespDTO> preview(RegistrationNameReqDTO request) {
        requireRegistrationPermission();
        return success(service.preview(request));
    }
}
