package cn.iocoder.yudao.module.asset.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskSaveReqVO;
import cn.iocoder.yudao.module.asset.service.quality.AssetQualityTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 资产质量任务")
@RestController
@RequestMapping("/asset/quality-task")
@Validated
public class AssetQualityTaskController {

    @Resource
    private AssetQualityTaskService qualityTaskService;

    /**
     * 创建资产质量任务。
     */
    @LogRecord(type = "数据资产-资产质量任务", subType = "创建资产质量任务", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了资产质量任务【{{#reqVO.taskName}}】，任务编号【{{#_ret.data}}】，资产【{{#reqVO.assetId}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建资产质量任务")
    @PreAuthorize("@ss.hasPermission('asset:quality-task:manage')")
    public CommonResult<Long> createTask(@Valid @RequestBody AssetQualityTaskSaveReqVO reqVO) {
        return success(qualityTaskService.createTask(reqVO));
    }

    /**
     * 更新资产质量任务。
     */
    @LogRecord(type = "数据资产-资产质量任务", subType = "更新资产质量任务", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "修改了资产质量任务【{{#reqVO.taskName}}】（编号{{#reqVO.id}}），资产【{{#reqVO.assetId}}】，调度类型【{{#reqVO.scheduleType}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新资产质量任务")
    @PreAuthorize("@ss.hasPermission('asset:quality-task:manage')")
    public CommonResult<Boolean> updateTask(@Valid @RequestBody AssetQualityTaskSaveReqVO reqVO) {
        qualityTaskService.updateTask(reqVO);
        return success(true);
    }

    /**
     * 删除资产质量任务。
     */
    @LogRecord(type = "数据资产-资产质量任务", subType = "删除资产质量任务", bizNo = "{{(#id) ?: 0}}",
            success = "删除了资产质量任务【编号{{#id}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除资产质量任务")
    @PreAuthorize("@ss.hasPermission('asset:quality-task:manage')")
    public CommonResult<Boolean> deleteTask(@RequestParam("id") Long id) {
        qualityTaskService.deleteTask(id);
        return success(true);
    }

    /**
     * 获得资产质量任务列表。
     */
    @GetMapping("/list")
    @Operation(summary = "获得资产质量任务列表")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:query')")
    public CommonResult<List<AssetQualityTaskRespVO>> getTaskList(
            @RequestParam("assetId") Long assetId) {
        return success(qualityTaskService.getTaskList(assetId));
    }

    /**
     * 执行资产质量任务（复用治理质量引擎）
     *
     *  @return 治理质量任务实例编号
     */
    @LogRecord(type = "数据资产-资产质量任务", subType = "执行资产质量任务", bizNo = "{{(#id) ?: 0}}",
            success = "发起了资产质量任务【编号{{#id}}】的执行，执行记录【{{#_ret.data}}】，完成状态以执行结果为准", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/execute")
    @Operation(summary = "执行资产质量任务")
    @PreAuthorize("@ss.hasPermission('asset:quality-task:manage')")
    public CommonResult<Long> executeTask(@RequestParam("id") Long id) {
        return success(qualityTaskService.executeTask(id));
    }

}
