package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTableVersionRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTableVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-模板版本 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报模板版本")
@RestController
@RequestMapping("/dataforge/fill-table-version")
@Validated
public class FillTableVersionController {

    @Resource
    private FillTableVersionService tableVersionService;

    /**
     * 获得填报表的版本列表。
     *
     * @param tableId 表编号
     * @return 填报表的版本列表结果集合
     */
    @GetMapping("/list-by-table")
    @Operation(summary = "获得填报表的版本列表")
    @Parameter(name = "tableId", description = "填报表编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:query')")
    public CommonResult<List<FillTableVersionRespVO>> getTableVersionListByTableId(
            @RequestParam("tableId") Long tableId) {
        return success(BeanUtils.toBean(tableVersionService.getTableVersionListByTableId(tableId),
                FillTableVersionRespVO.class));
    }

}
