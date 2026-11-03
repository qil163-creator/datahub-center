package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillMergeBatchPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillMergeBatchRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillMergeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-合并批次 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报合并记录")
@RestController
@RequestMapping("/dataforge/fill-merge-batch")
@Validated
public class FillMergeController {

    @Resource
    private FillMergeService mergeService;

    /**
     * 获得合并批次分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 合并批次分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得合并批次分页")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-merge:query')")
    public CommonResult<PageResult<FillMergeBatchRespVO>> getBatchPage(@Valid FillMergeBatchPageReqVO pageReqVO) {
        return success(mergeService.getBatchPage(pageReqVO));
    }

    /**
     * 获得合并批次。
     *
     * @param id 记录编号
     * @return 填报合并批次响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得合并批次")
    @Parameter(name = "id", description = "批次编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-merge:query')")
    public CommonResult<FillMergeBatchRespVO> getBatch(@RequestParam("id") Long id) {
        return success(mergeService.getBatch(id));
    }

    /**
     * 重试失败的合并批次。
     *
     * @param id 记录编号
     * @return 填报合并
     */
    @PutMapping("/retry")
    @Operation(summary = "重试失败的合并批次", description = "置回待执行，由合并 Job 拾取；不要求重新审批")
    @Parameter(name = "id", description = "批次编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-merge:retry')")
    public CommonResult<Boolean> retryBatch(@RequestParam("id") Long id) {
        mergeService.retryBatch(id);
        return success(true);
    }

}
