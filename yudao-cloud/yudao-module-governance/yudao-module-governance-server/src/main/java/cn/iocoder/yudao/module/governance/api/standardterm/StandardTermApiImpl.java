package cn.iocoder.yudao.module.governance.api.standardterm;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.api.standardterm.dto.StandardTermRespDTO;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcFilter;
import cn.iocoder.yudao.module.governance.service.standardterm.StandardTermService;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
public class StandardTermApiImpl implements StandardTermApi {

    @Resource
    private StandardTermService standardTermService;

    @Resource
    private HttpServletRequest servletRequest;

    /**
     * 检索平台内启用术语，按名称或别名匹配，最多 50 条。
     */
    @Override
    @PermitAll // Only the narrow mTLS gate grants access; fail closed if registration is missing.
    public CommonResult<List<StandardTermRespDTO>> getEnabledTermList(String keyword) {
        StandardTermRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(BeanUtils.toBean(
                standardTermService.getEnabledStandardTermList(keyword), StandardTermRespDTO.class));
    }

    /**
     * 批量读取平台内当前术语，含停用术语，删除或不可见的编号不返回。
     */
    @Override
    @PermitAll
    public CommonResult<List<StandardTermRespDTO>> getTermList(List<Long> ids) {
        StandardTermRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(BeanUtils.toBean(
                standardTermService.getStandardTermList(ids), StandardTermRespDTO.class));
    }

}
