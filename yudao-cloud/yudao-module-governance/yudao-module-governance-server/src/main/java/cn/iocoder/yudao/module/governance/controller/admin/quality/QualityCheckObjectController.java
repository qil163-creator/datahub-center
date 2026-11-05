package cn.iocoder.yudao.module.governance.controller.admin.quality;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.service.quality.QualityCheckObjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据治理-数据质量-检查对象 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据质量-检查对象")
@RestController
@RequestMapping("/governance/quality-check-object")
@Validated
public class QualityCheckObjectController {

    @Resource
    private QualityCheckObjectService checkObjectService;

    /**
     * 创建检查对象。
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据治理-数据质量-检查对象", bizId = "#result")
    @Operation(summary = "创建检查对象")
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:create')")
    public CommonResult<Long> createCheckObject(@Valid @RequestBody QualityCheckObjectSaveReqVO createReqVO) {
        return success(checkObjectService.createCheckObject(createReqVO));
    }

    /**
     * 更新检查对象。
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据治理-数据质量-检查对象", bizId = "#updateReqVO.id")
    @Operation(summary = "更新检查对象")
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:update')")
    public CommonResult<Boolean> updateCheckObject(@Valid @RequestBody QualityCheckObjectSaveReqVO updateReqVO) {
        checkObjectService.updateCheckObject(updateReqVO);
        return success(true);
    }

    /**
     * 删除检查对象。
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据治理-数据质量-检查对象", bizId = "#id")
    @Operation(summary = "删除检查对象")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:delete')")
    public CommonResult<Boolean> deleteCheckObject(@RequestParam("id") Long id) {
        checkObjectService.deleteCheckObject(id);
        return success(true);
    }

    /**
     * 获得检查对象。
     */
    @GetMapping("/get")
    @Operation(summary = "获得检查对象")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:query')")
    public CommonResult<QualityCheckObjectRespVO> getCheckObject(@RequestParam("id") Long id) {
        QualityCheckObjectDO checkObject = checkObjectService.getCheckObject(id);
        return success(BeanUtils.toBean(checkObject, QualityCheckObjectRespVO.class));
    }

    /**
     * 获得检查对象分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得检查对象分页")
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:query')")
    public CommonResult<PageResult<QualityCheckObjectRespVO>> getCheckObjectPage(
            @Valid QualityCheckObjectPageReqVO pageReqVO) {
        PageResult<QualityCheckObjectDO> pageResult = checkObjectService.getCheckObjectPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, QualityCheckObjectRespVO.class));
    }

    /**
     * 获得检查对象精简列表。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得检查对象精简列表")
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:query')")
    public CommonResult<List<QualityCheckObjectRespVO>> getCheckObjectSimpleList() {
        return success(BeanUtils.toBean(checkObjectService.getCheckObjectSimpleList(), QualityCheckObjectRespVO.class));
    }

}
