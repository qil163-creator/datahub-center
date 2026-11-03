package cn.iocoder.yudao.module.modeling.controller.admin.planning;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerListReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerPrefixPreviewRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerUpdateStatusReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.service.planning.DwLayerService;
import cn.iocoder.yudao.module.modeling.service.planning.DwLayerPresentationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数仓分层")
@RestController
@RequestMapping("/modeling/dwlayer")
@Validated
public class DwLayerController {

    @Resource
    private DwLayerService dwLayerService;

    @Resource
    private DwLayerPresentationService presentationService;

    /**
     * 创建数仓分层。
     *
     * @param createReqVO 创建参数
     * @return 数仓分层
     */
    @PostMapping("/create")
    @Operation(summary = "创建数仓分层")
    @PreAuthorize("@ss.hasPermission('modeling:dwlayer:create')")
    public CommonResult<Long> createDwLayer(@Valid @RequestBody DwLayerSaveReqVO createReqVO) {
        return success(dwLayerService.createDwLayer(createReqVO));
    }

    /**
     * 更新数仓分层。
     *
     * @param updateReqVO 更新参数
     * @return 数仓分层
     */
    @PutMapping("/update")
    @Operation(summary = "更新数仓分层")
    @PreAuthorize("@ss.hasPermission('modeling:dwlayer:update')")
    public CommonResult<Boolean> updateDwLayer(@RequestBody DwLayerSaveReqVO updateReqVO) {
        dwLayerService.updateDwLayer(updateReqVO);
        return success(true);
    }

    /**
     * 更新数仓分层状态。
     *
     * @param reqVO 请求参数
     * @return 数仓分层
     */
    @PutMapping("/update-status")
    @Operation(summary = "更新数仓分层状态")
    @PreAuthorize("@ss.hasPermission('modeling:dwlayer:update')")
    public CommonResult<Boolean> updateDwLayerStatus(@Valid @RequestBody DwLayerUpdateStatusReqVO reqVO) {
        dwLayerService.updateDwLayerStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    /**
     * 删除数仓分层。
     *
     * @param id 记录编号
     * @return 数仓分层
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除数仓分层")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:dwlayer:delete')")
    public CommonResult<Boolean> deleteDwLayer(@RequestParam("id") Long id) {
        dwLayerService.deleteDwLayer(id);
        return success(true);
    }


    /**
     * 获得数仓分层。
     *
     * @param id 记录编号
     * @return 数仓分层响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得数仓分层")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('modeling:dwlayer:query')")
    public CommonResult<DwLayerRespVO> getDwLayer(@RequestParam("id") Long id) {
        DwLayerDO dwLayer = dwLayerService.getDwLayer(id);
        return success(dwLayer == null ? null : presentationService.present(List.of(dwLayer)).getFirst());
    }

    /**
     * 预览修改表前缀后的候选表名与差异，只读，不重命名已有物理表。
     *
     * @param id 记录编号
     * @param tablePrefix 表前缀
     * @return 数仓分层前缀预览响应数据
     */
    @GetMapping("/prefix-preview")
    @Operation(summary = "预览修改表前缀后的候选表名与差异，只读，不重命名已有物理表")
    @Parameter(name = "id", description = "分层编号", required = true, example = "6")
    @Parameter(name = "tablePrefix", description = "候选表前缀，空表示去掉前缀", example = "dwd_")
    @PreAuthorize("@ss.hasPermission('modeling:dwlayer:query')")
    public CommonResult<DwLayerPrefixPreviewRespVO> previewTablePrefix(
            @RequestParam("id") Long id,
            @RequestParam(value = "tablePrefix", required = false) String tablePrefix) {
        return success(dwLayerService.previewTablePrefix(id, tablePrefix));
    }

    /**
     * 获得数仓分层列表。
     *
     * @param listReqVO 列表请求参数
     * @return 数仓分层列表结果集合
     */
    @GetMapping("/list")
    @Operation(summary = "获得数仓分层列表")
    @PreAuthorize("@ss.hasPermission('modeling:dwlayer:query')")
    public CommonResult<List<DwLayerRespVO>> getDwLayerList(@Valid DwLayerListReqVO listReqVO) {
        List<DwLayerDO> list = dwLayerService.getDwLayerList(listReqVO);
        return success(presentationService.present(list));
    }

    /**
     * 导出数仓分层 Excel。
     *
     * @param listReqVO 列表请求参数
     * @param response 响应对象
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数仓分层 Excel")
    @PreAuthorize("@ss.hasPermission('modeling:dwlayer:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportDwLayerExcel(@Valid DwLayerListReqVO listReqVO,
            HttpServletResponse response) throws IOException {
        List<DwLayerDO> list = dwLayerService.getDwLayerList(listReqVO);
        // 导出 Excel
        ExcelUtils.write(response, "数仓分层.xls", "数据", DwLayerRespVO.class,
                BeanUtils.toBean(list, DwLayerRespVO.class));
    }

}
