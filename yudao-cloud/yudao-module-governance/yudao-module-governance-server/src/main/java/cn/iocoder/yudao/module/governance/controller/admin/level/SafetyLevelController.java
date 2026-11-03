package cn.iocoder.yudao.module.governance.controller.admin.level;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.service.level.SafetyLevelService;
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

@Tag(name = "管理后台 - 数据分级")
@RestController
@RequestMapping("/governance/safety-level")
@Validated
public class SafetyLevelController {

    @Resource
    private SafetyLevelService levelService;

    /**
     * 创建数据分级
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @Operation(summary = "创建数据分级")
    @PreAuthorize("@ss.hasPermission('governance:level:create')")
    public CommonResult<Long> createLevel(@Valid @RequestBody SafetyLevelSaveReqVO createReqVO) {
        return success(levelService.createLevel(createReqVO));
    }

    /**
     * 更新数据分级
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @Operation(summary = "更新数据分级")
    @PreAuthorize("@ss.hasPermission('governance:level:update')")
    public CommonResult<Boolean> updateLevel(@Valid @RequestBody SafetyLevelSaveReqVO updateReqVO) {
        levelService.updateLevel(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据分级
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除数据分级")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:level:delete')")
    public CommonResult<Boolean> deleteLevel(@RequestParam("id") Long id) {
        levelService.deleteLevel(id);
        return success(true);
    }

    /**
     * 批量删除数据分级。
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @Operation(summary = "批量删除数据分级")
    @PreAuthorize("@ss.hasPermission('governance:level:delete')")
    public CommonResult<Boolean> deleteLevelList(@RequestParam("ids") List<Long> ids) {
        levelService.deleteLevelListByIds(ids);
        return success(true);
    }

    /**
     * 获得数据分级
     *
     *  @param id 编号
     *  @return 数据分级
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据分级")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:level:query')")
    public CommonResult<SafetyLevelRespVO> getLevel(@RequestParam("id") Long id) {
        SafetyLevelDO level = levelService.getLevel(id);
        return success(BeanUtils.toBean(level, SafetyLevelRespVO.class));
    }

    /**
     * 获得数据分级分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据分级分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据分级分页")
    @PreAuthorize("@ss.hasPermission('governance:level:query')")
    public CommonResult<PageResult<SafetyLevelRespVO>> getLevelPage(@Valid SafetyLevelPageReqVO pageReqVO) {
        PageResult<SafetyLevelDO> pageResult = levelService.getLevelPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, SafetyLevelRespVO.class));
    }

    /**
     * 导出数据分级 Excel。
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据分级 Excel")
    @PreAuthorize("@ss.hasPermission('governance:level:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportLevelExcel(@Valid SafetyLevelPageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<SafetyLevelDO> list = levelService.getLevelPage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "数据分级.xls", "数据", SafetyLevelRespVO.class,
                BeanUtils.toBean(list, SafetyLevelRespVO.class));
    }

}
