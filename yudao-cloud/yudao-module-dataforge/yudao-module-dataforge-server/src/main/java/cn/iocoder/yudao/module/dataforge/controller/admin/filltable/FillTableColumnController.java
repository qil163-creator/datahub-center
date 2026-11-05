package cn.iocoder.yudao.module.dataforge.controller.admin.filltable;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableColumnSaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.filltable.FillTableColumnService;
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

@Tag(name = "管理后台 - 数据填报-填报表字段")
@RestController
@RequestMapping("/dataforge/fill-table-column")
@Validated
public class FillTableColumnController {

    @Resource
    private FillTableColumnService columnService;

    /**
     * 获得填报表的字段列表（按排序）。
     *
     * @param tableId 表编号
     * @return 填报表的字段列表（按排序）结果集合
     */
    @GetMapping("/list-by-table")
    @Operation(summary = "获得填报表的字段列表（按排序）")
    @Parameter(name = "tableId", description = "填报表编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:query')")
    public CommonResult<List<FillTableColumnRespVO>> getColumnListByTableId(@RequestParam("tableId") Long tableId) {
        return success(columnService.getColumnListByTableId(tableId));
    }

    /**
     * 创建填报表字段。
     *
     * @param createReqVO 创建参数
     * @return 填报表列
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-数据填报-填报表字段", subType = "创建填报表字段", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "在填报表【{{(#createReqVO.tableId) ?: '未设置'}}】创建了字段【{{(#createReqVO.cnName) ?: '未设置'}}】（字段名【{{(#createReqVO.name) ?: '未设置'}}】），字段类型【{{(#createReqVO.fieldType) ?: '未设置'}}】，允许为空【{{(#createReqVO.nullable) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建填报表字段")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:update')")
    public CommonResult<Long> createColumn(@Valid @RequestBody FillTableColumnSaveReqVO createReqVO) {
        return success(columnService.createColumn(createReqVO));
    }

    /**
     * 更新填报表字段。
     *
     * @param updateReqVO 更新参数
     * @return 填报表列
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-数据填报-填报表字段", subType = "更新填报表字段", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了填报表【{{(#updateReqVO.tableId) ?: '未设置'}}】的字段【{{(#operateName) ?: '未设置'}}】，字段名改为【{{(#updateReqVO.name) ?: '未设置'}}】，中文名【{{(#updateReqVO.cnName) ?: '未设置'}}】，类型【{{(#updateReqVO.fieldType) ?: '未设置'}}】，允许为空【{{(#updateReqVO.nullable) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新填报表字段")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:update')")
    public CommonResult<Boolean> updateColumn(@Valid @RequestBody FillTableColumnSaveReqVO updateReqVO) {
        String operateName = "编号" + updateReqVO.getId();
        try {
            var operateObject = columnService.getColumnListByTableId(updateReqVO.getTableId()).stream().filter(item -> java.util.Objects.equals(item.getId(), updateReqVO.getId())).findFirst().orElse(null);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        columnService.updateColumn(updateReqVO);
        return success(true);
    }

    /**
     * 删除填报表字段。
     *
     * @param id 记录编号
     * @return 填报表列
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-数据填报-填报表字段", subType = "删除填报表字段", bizNo = "{{(#id) ?: 0}}",
            success = "删除了填报字段【{{(#id) ?: '未设置'}}】的配置，后续发布不再使用该字段", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除填报表字段")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:update')")
    public CommonResult<Boolean> deleteColumn(@RequestParam("id") Long id) {
        columnService.deleteColumn(id);
        return success(true);
    }

}
