package cn.iocoder.yudao.module.governance.api.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyMaskExemptRespDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 数据安全 RPC 接口：敏感访问审计上报 + 脱敏白名单豁免查询。
 * 供数据服务（supply）等模块在数据出口处调用。
 *
 * @author Li
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 数据安全")
public interface SafetySecurityApi {

    String PREFIX = ApiConstants.PREFIX + "/safety";

    /**
     * 记录敏感数据访问审计日志。
     */
    @PostMapping(PREFIX + "/access-log")
    @Operation(summary = "记录敏感数据访问审计日志")
    CommonResult<Boolean> recordAccessLog(@Valid @RequestBody SafetyAccessLogCreateReqDTO reqDTO);

    /**
     * 查询用户是否命中脱敏白名单（已审批 + 启用 + 有效期内）。
     */
    @GetMapping(PREFIX + "/mask-exempt")
    @Operation(summary = "查询用户是否命中脱敏白名单（已审批 + 启用 + 有效期内）")
    CommonResult<SafetyMaskExemptRespDTO> getMaskExempt(@RequestParam("userId") Long userId,
            @RequestParam("scene") String scene);

    /**
     * 动态脱敏：按字段分类、白名单豁免和场景开关处理查询结果。
     * <p>
     * 调用方须已完成对象与字段读取授权，并提交每个输出列的物理来源。无法核验时失败关闭，不返回原值。
     */
    @PostMapping(PREFIX + "/mask-rows")
    @Operation(summary = "动态脱敏查询结果（失败关闭）")
    CommonResult<cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO> maskRows(
            @Valid @RequestBody cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO reqDTO);

    /**
     * 持久化前脱敏：结果会被保存并展示给多个用户（如填报模板固化的下拉取值），没有确定的查看者。
     * <p>
     * 不看场景开关、不看白名单豁免：列来源命中分类、被标记敏感，或来源无法追溯且涉及敏感表时整体替换为不可逆掩码，
     * 并在 maskedColumns 中说明原因。分类无法解析时返回错误，调用方须失败关闭。不写访问审计。
     */
    @PostMapping(PREFIX + "/mask-for-storage")
    @Operation(summary = "持久化前脱敏（不看白名单豁免，失败关闭）")
    CommonResult<cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO> maskForStorage(
            @Valid @RequestBody cn.iocoder.yudao.module.governance.api.security.dto.SafetyStorageMaskReqDTO reqDTO);

}
