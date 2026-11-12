package cn.iocoder.yudao.module.governance.controller.admin.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyMaskPreviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyMaskPreviewRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyMaskSceneRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyMaskSceneUpdateReqVO;
import cn.iocoder.yudao.module.governance.service.security.SafetyMaskSceneService;
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
 * 管理后台 - 动态脱敏。
 * <p>
 * 数据范围：场景开关是平台级安全策略，按权限码控制，平台内全可见；策略预览只读元数据与分类。
 */
@Tag(name = "管理后台 - 动态脱敏")
@RestController
@RequestMapping("/governance/dynamic-mask")
@Validated
public class SafetyDynamicMaskController {

    @Resource
    private SafetyMaskSceneService sceneService;

    /**
     * 获得全部动态脱敏场景。
     */
    @GetMapping("/scene/list")
    @Operation(summary = "获得全部动态脱敏场景")
    @PreAuthorize("@ss.hasPermission('governance:dynamic-mask:query')")
    public CommonResult<List<SafetyMaskSceneRespVO>> listScenes() {
        return success(sceneService.listScenes());
    }

    /**
     * 更新动态脱敏场景开关。
     */
    @PutMapping("/scene/update")
    @Operation(summary = "更新动态脱敏场景开关")
    @PreAuthorize("@ss.hasPermission('governance:dynamic-mask:update')")
    @LogRecord(type = "数据治理-动态脱敏", subType = "更新场景开关", bizNo = "{{#reqVO.sceneCode}}",
            success = "将场景【{{#reqVO.sceneCode}}】设置为【{{#reqVO.enabled ? '启用' : '关闭'}}】，原因【{{(#reqVO.remark) ?: '无'}}】",
            extra = "BUSINESS_OPERATION")
    public CommonResult<Boolean> updateScene(@Valid @RequestBody SafetyMaskSceneUpdateReqVO reqVO) {
        sceneService.updateScene(reqVO);
        return success(true);
    }

    /**
     * 预览某用户在某场景下读取一张表时各列的处理方式。
     */
    @PostMapping("/preview")
    @Operation(summary = "预览动态脱敏策略")
    @PreAuthorize("@ss.hasPermission('governance:dynamic-mask:query')")
    public CommonResult<SafetyMaskPreviewRespVO> preview(@Valid @RequestBody SafetyMaskPreviewReqVO reqVO) {
        return success(sceneService.preview(reqVO));
    }
}
