package cn.iocoder.yudao.module.asset.api.sensitive;

import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.asset.api.sensitive.dto.*;
import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.AssetSensitivePageReqVO;
import cn.iocoder.yudao.module.asset.framework.security.AssetSensitivePolicyRpcFilter;
import cn.iocoder.yudao.module.asset.service.sensitive.*;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class AssetSensitivePolicyApiImpl implements AssetSensitivePolicyApi {

    @Resource
    private AssetSensitiveListService sensitiveService;

    @Resource
    private HttpServletRequest servletRequest;

    /**
     * 读取规则分页。
     */
    @Override
    @PreAuthorize("@ss.hasPermission('asset:sensitive-list:query')")
    public CommonResult<PageResult<AssetSensitiveFactRespDTO>> getRulePage(AssetRuleSensitivePageReqDTO request) {
        AssetSensitivePolicyRpcFilter.requireVerified(servletRequest);
        var query = BeanUtils.toBean(request, AssetSensitivePageReqVO.class);
        var page = sensitiveService.page(query);
        return CommonResult.success(
                new PageResult<>(BeanUtils.toBean(page.getList(), AssetSensitiveFactRespDTO.class), page.getTotal()));
    }
}
