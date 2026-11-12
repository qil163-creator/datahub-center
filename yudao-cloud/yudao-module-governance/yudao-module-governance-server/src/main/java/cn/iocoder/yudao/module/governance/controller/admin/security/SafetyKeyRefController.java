package cn.iocoder.yudao.module.governance.controller.admin.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyKeyCenterStatusRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyKeyRefCreateReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyKeyRefRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyKeyRefStatusReqVO;
import cn.iocoder.yudao.module.governance.service.security.SafetyKeyRefService;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 字段加密密钥引用。
 * <p>
 * 数据范围：平台级安全配置，按权限码控制，平台内全可见；接口不返回任何密钥材料或派生盐。
 */
@Tag(name = "管理后台 - 字段加密密钥引用")
@RestController
@RequestMapping("/governance/key-ref")
@Validated
public class SafetyKeyRefController {

    @Resource
    private SafetyKeyRefService keyRefService;

    /**
     * 密钥引用列表。
     */
    @GetMapping("/list")
    @Operation(summary = "获得密钥引用列表")
    @PreAuthorize("@ss.hasPermission('governance:key-ref:query')")
    public CommonResult<List<SafetyKeyRefRespVO>> list() {
        return success(keyRefService.list());
    }

    /**
     * 密钥中心状态。
     */
    @GetMapping("/status")
    @Operation(summary = "获得密钥中心状态")
    @PreAuthorize("@ss.hasPermission('governance:key-ref:query')")
    public CommonResult<SafetyKeyCenterStatusRespVO> status() {
        return success(keyRefService.status());
    }

    /**
     * 新建密钥引用。
     */
    @PostMapping("/create")
    @Operation(summary = "新建密钥引用")
    @PreAuthorize("@ss.hasPermission('governance:key-ref:create')")
    @LogRecord(type = "数据治理-字段加密", subType = "新建密钥引用", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "新建密钥引用【{{#reqVO.keyCode}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0}}")
    public CommonResult<Long> create(@Valid @RequestBody SafetyKeyRefCreateReqVO reqVO) {
        return success(keyRefService.create(reqVO));
    }

    /**
     * 启停密钥引用。
     */
    @PutMapping("/update-status")
    @Operation(summary = "启停密钥引用")
    @PreAuthorize("@ss.hasPermission('governance:key-ref:update')")
    @LogRecord(type = "数据治理-字段加密", subType = "启停密钥引用", bizNo = "{{#reqVO.id}}",
            success = "将密钥引用【{{#reqVO.id}}】设置为【{{#reqVO.status == 0 ? '启用' : '停用'}}】",
            extra = "BUSINESS_OPERATION")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody SafetyKeyRefStatusReqVO reqVO) {
        keyRefService.updateStatus(reqVO);
        return success(true);
    }
}
