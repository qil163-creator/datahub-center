package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionCreateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.service.fill.FillCorrectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;

/**
 * 在线填报-数据更正 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报数据更正")
@RestController
@RequestMapping("/dataforge/fill-correction")
@Validated
public class FillCorrectionController {

    @Resource
    private FillCorrectionService correctionService;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillTaskMapper taskMapper;

    /**
     * 发起数据更正。
     *
     * @param createReqVO 创建参数
     * @return 填报更正
     */
    @PostMapping("/create")
    @Operation(summary = "发起数据更正", description = "仅最新已合并快照可更正；实例转入整改中，重新提交后走新 BPM 审批并幂等覆盖")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-correction:create')")
    public CommonResult<Long> createCorrection(@Valid @RequestBody FillCorrectionCreateReqVO createReqVO) {
        return success(correctionService.createCorrection(createReqVO));
    }

    /**
     * 取消更正（仅草拟中且尚未重新提交）。
     *
     * @param id 记录编号
     * @return 填报更正
     */
    @PutMapping("/cancel")
    @Operation(summary = "取消更正（仅草拟中且尚未重新提交）")
    @Parameter(name = "id", description = "更正实例编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-correction:create')")
    public CommonResult<Boolean> cancelCorrection(@RequestParam("id") Long id) {
        correctionService.cancelCorrection(id);
        return success(true);
    }

    /**
     * 获得数据更正分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 数据更正分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据更正分页")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-merge:query')")
    public CommonResult<PageResult<FillCorrectionRespVO>> getCorrectionPage(
            @Valid FillCorrectionPageReqVO pageReqVO) {
        PageResult<FillCorrectionRespVO> pageResult = BeanUtils.toBean(
                correctionService.getCorrectionPage(pageReqVO), FillCorrectionRespVO.class);
        // 补充组织与任务名称
        Set<Long> workItemIds = convertSet(pageResult.getList(), FillCorrectionRespVO::getWorkItemId);
        Set<Long> taskIds = convertSet(pageResult.getList(), FillCorrectionRespVO::getTaskId);
        if (!workItemIds.isEmpty()) {
            Map<Long, FillWorkItemDO> itemMap = workItemMapper.selectListByIds(workItemIds).stream()
                    .collect(java.util.stream.Collectors.toMap(FillWorkItemDO::getId, item -> item));
            Map<Long, FillTaskDO> taskMap = taskMapper.selectByIds(taskIds).stream()
                    .collect(java.util.stream.Collectors.toMap(FillTaskDO::getId, task -> task));
            pageResult.getList().forEach(respVO -> {
                FillWorkItemDO item = itemMap.get(respVO.getWorkItemId());
                if (item != null) {
                    respVO.setOrgName(item.getOrgName());
                }
                FillTaskDO task = taskMap.get(respVO.getTaskId());
                if (task != null) {
                    respVO.setTaskName(task.getName());
                }
            });
        }
        return success(pageResult);
    }

}
