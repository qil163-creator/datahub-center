package cn.iocoder.yudao.module.dataforge.controller.admin.system;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.system.vo.SystemSourcePageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.system.vo.SystemSourceRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.system.vo.SystemSourceSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.systemsource.SystemSourceDO;
import cn.iocoder.yudao.module.dataforge.service.system.SystemSourceService;
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
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

@Tag(name = "管理后台 - 来源系统")
@RestController
@RequestMapping("/dataforge/system-source")
@Validated
public class SystemSourceController {

    @Resource
    private SystemSourceService systemSourceService;

    /**
     * 创建来源系统。
     *
     * @param createReqVO 创建参数
     * @return 业务系统
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-来源系统", subType = "创建来源系统", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了来源系统【{{(#createReqVO.name) ?: '未设置'}}】，系统类型【{{(#createReqVO.type) ?: '未设置'}}】，状态【{{(#createReqVO.status) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建来源系统")
    @PreAuthorize("@ss.hasPermission('dataforge:system-source:create')")
    public CommonResult<Long> createSystemSource(@Valid @RequestBody SystemSourceSaveReqVO createReqVO) {
        return success(systemSourceService.createSystemSource(createReqVO));
    }

    /**
     * 更新来源系统。
     *
     * @param updateReqVO 更新参数
     * @return 业务系统
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-来源系统", subType = "更新来源系统", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了来源系统【{{(#operateName) ?: '未设置'}}】，名称从【{{(#operateName) ?: '未设置'}}】改为【{{(#updateReqVO.name) ?: '未设置'}}】，系统类型【{{(#updateReqVO.type) ?: '未设置'}}】，状态【{{(#updateReqVO.status) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新来源系统")
    @PreAuthorize("@ss.hasPermission('dataforge:system-source:update')")
    public CommonResult<Boolean> updateSystemSource(@Valid @RequestBody SystemSourceSaveReqVO updateReqVO) {
        String operateName = "编号" + updateReqVO.getId();
        try {
            var operateObject = systemSourceService.getSystemSource(updateReqVO.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        systemSourceService.updateSystemSource(updateReqVO);
        return success(true);
    }

    /**
     * 删除来源系统。
     *
     * @param id 记录编号
     * @return 业务系统
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-来源系统", subType = "删除来源系统", bizNo = "{{(#id) ?: 0}}",
            success = "删除了来源系统【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除来源系统")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:system-source:delete')")
    public CommonResult<Boolean> deleteSystemSource(@RequestParam("id") Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = systemSourceService.getSystemSource(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        systemSourceService.deleteSystemSource(id);
        return success(true);
    }

    /**
     * 批量删除来源系统。
     *
     * @param ids 记录编号集合
     * @return 业务系统
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @LogRecord(type = "数据开发-来源系统", subType = "批量删除来源系统", bizNo = "{{(#ids) ?: 0}}",
            success = "批量删除了来源系统【{{(#operateNames) ?: '未设置'}}】，请求编号【{{(#ids) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除来源系统")
    @PreAuthorize("@ss.hasPermission('dataforge:system-source:delete')")
    public CommonResult<Boolean> deleteSystemSourceList(@RequestParam("ids") List<Long> ids) {
        var operateNames = new java.util.ArrayList<String>();
        for (Long operateId : ids) {
            try {
                var operateObject = systemSourceService.getSystemSource(operateId);
                if (operateObject != null) {
                    operateNames.add(java.util.Objects.toString(operateObject.getName(), "编号" + operateId) + "（编号" + operateId + "）");
                }
            } catch (RuntimeException ignored) {
                // 仅尽力读取日志名称，业务批量入口继续负责缺失对象的处理。
            }
        }
        LogRecordContext.putVariable("operateNames", operateNames);

        systemSourceService.deleteSystemSourceListByIds(ids);
        return success(true);
    }

    /**
     * 获得来源系统。
     *
     * @param id 记录编号
     * @return 业务系统响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得来源系统")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('dataforge:system-source:query')")
    public CommonResult<SystemSourceRespVO> getSystemSource(@RequestParam("id") Long id) {
        SystemSourceDO systemSource = systemSourceService.getSystemSource(id);
        return success(buildSystemSourceRespVO(systemSource));
    }

    /**
     * 获得来源系统分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 来源系统分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得来源系统分页")
    @PreAuthorize("@ss.hasPermission('dataforge:system-source:query')")
    public CommonResult<PageResult<SystemSourceRespVO>> getSystemSourcePage(@Valid SystemSourcePageReqVO pageReqVO) {
        PageResult<SystemSourceDO> pageResult = systemSourceService.getSystemSourcePage(pageReqVO);
        return success(buildSystemSourceRespVOPage(pageResult));
    }

    /**
     * 导出来源系统 Excel。
     *
     * @param pageReqVO 分页查询条件
     * @param response 响应对象
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出来源系统 Excel")
    @PreAuthorize("@ss.hasPermission('dataforge:system-source:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportSystemSourceExcel(@Valid SystemSourcePageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<SystemSourceDO> list = systemSourceService.getSystemSourcePage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "来源系统.xls", "数据", SystemSourceRespVO.class,
                buildSystemSourceRespVOList(list));
    }

    /**
     * 构造业务系统响应数据。
     *
     * @param systemSource 业务系统
     * @return 业务系统响应数据
     */
    private SystemSourceRespVO buildSystemSourceRespVO(SystemSourceDO systemSource) {
        SystemSourceRespVO respVO = BeanUtils.toBean(systemSource, SystemSourceRespVO.class);
        if (systemSource == null) {
            return respVO;
        }
        respVO.setSortOrder(systemSource.getSortOrder());
        respVO.setUrl(systemSource.getUrl());
        respVO.setUpdateTime(systemSource.getUpdateTime());
        return respVO;
    }

    /**
     * 构造业务系统响应数据列表。
     *
     * @param list 待处理列表
     * @return 构造业务系统响应数据列表结果集合
     */
    private List<SystemSourceRespVO> buildSystemSourceRespVOList(List<SystemSourceDO> list) {
        return convertList(list, this::buildSystemSourceRespVO);
    }

    /**
     * 构造业务系统响应数据分页。
     *
     * @param pageResult 分页结果
     * @return 构造业务系统响应数据分页结果
     */
    private PageResult<SystemSourceRespVO> buildSystemSourceRespVOPage(PageResult<SystemSourceDO> pageResult) {
        return new PageResult<>(buildSystemSourceRespVOList(pageResult.getList()), pageResult.getTotal());
    }

}
