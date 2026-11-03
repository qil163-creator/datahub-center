package cn.iocoder.yudao.module.modeling.api.registration;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.api.registration.dto.*;
import cn.iocoder.yudao.module.modeling.enums.ApiConstants;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** 注册表单的建模领域上下文，只读选项和推荐名称，不执行注册或 DDL。 */
@FeignClient(name = ApiConstants.NAME, contextId = "registrationContextApi")
@Tag(name = "RPC 服务 - 资产注册建模上下文")
public interface RegistrationContextApi {

    String PREFIX = ApiConstants.PREFIX + "/registration-context";

    /**
     * 读取全平台已启用的业务选项白名单。
     *
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     * @param subjectDomainId 主题域编号
     * @return 登记上下文响应数据
     */
    @GetMapping(PREFIX)
    @Operation(summary = "读取全平台已启用的业务选项白名单")
    CommonResult<RegistrationContextRespDTO> context(
            @RequestParam(value = "dataDomainId", required = false) Long dataDomainId,
            @RequestParam(value = "tableType", required = false) String tableType,
            @RequestParam(value = "subjectDomainId", required = false) Long subjectDomainId);

    /**
     * 按真实业务选择和持久命名规则生成推荐名称。
     *
     * @param request 请求参数
     * @return 登记名称响应数据
     */
    @PostMapping(PREFIX + "/name-preview")
    @Operation(summary = "按真实业务选择和持久命名规则生成推荐名称")
    CommonResult<RegistrationNameRespDTO> preview(@Valid @RequestBody RegistrationNameReqDTO request);
}
