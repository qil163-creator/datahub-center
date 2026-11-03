package cn.iocoder.yudao.module.system.api.user;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserOptionPageReqDTO;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserOptionRespDTO;
import cn.iocoder.yudao.module.system.enums.ApiConstants;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;

/** 白名单账号候选的独立认证通道，不改变原用户 RPC 的客户端协议。 */
@FeignClient(name=ApiConstants.NAME,contextId="adminUserOptionApi",configuration=AdminUserOptionClientConfiguration.class)
public interface AdminUserOptionApi {
    String PREFIX=ApiConstants.PREFIX+"/user-option";
    @GetMapping(PREFIX+"/page")
    CommonResult<PageResult<AdminUserOptionRespDTO>> getUserOptionPage(@SpringQueryMap @Valid AdminUserOptionPageReqDTO request);
}
