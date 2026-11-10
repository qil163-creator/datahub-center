package cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.service.standardcodeitem.StandardCodeItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据标准--码值")
@RestController
@RequestMapping("/governance/standard-code-item")
@Validated
public class StandardCodeItemController {

    @Resource
    private StandardCodeItemService standardCodeItemService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService catalog;

    /**
     * 创建码值
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-码值", subType = "创建数据标准--码值",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了标准码值【{{(#createReqVO.label) ?: '未设置'}}】，编码【{{(#createReqVO.value) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建数据标准--码值")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:create')")
    public CommonResult<Integer> createStandardCodeItem(@Valid @RequestBody StandardCodeItemSaveReqVO createReqVO) {
        return success(standardCodeItemService.createStandardCodeItem(createReqVO));
    }

    /**
     * 更新码值
     *
     *  @param updateReqVO 更新信息
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-码值", subType = "更新数据标准--码值",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了标准码值，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.label) ?: '未设置'}}】，提交编码【{{(#updateReqVO.value) ?: '未设置'}}】，目标状态【{{(#updateReqVO.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新数据标准--码值")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:update')")
    public CommonResult<Boolean> updateStandardCodeItem(@Valid @RequestBody StandardCodeItemSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        standardCodeItemService.updateStandardCodeItem(updateReqVO);
        return success(true);
    }

    /**
     * 删除码值
     *
     *  @param id 编号
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-码值", subType = "删除数据标准--码值",
            bizNo = "{{(#id) ?: 0}}", success = "删除了标准码值【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除数据标准--码值")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:delete')")
    public CommonResult<Boolean> deleteStandardCodeItem(@RequestParam("id") Integer id) {
        captureAuditContext(id);
        standardCodeItemService.deleteStandardCodeItem(id);
        return success(true);
    }

    /**
     * 批量删除数据标准--码值。
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @LogRecord(type = "数据治理-码值", subType = "批量删除数据标准--码值",
            bizNo = "{{(#ids) ?: 0}}", success = "提交批量删除标准码值，对象编号【{{(#ids) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除数据标准--码值")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:delete')")
    public CommonResult<Boolean> deleteStandardCodeItemList(@RequestParam("ids") List<Integer> ids) {
        standardCodeItemService.deleteStandardCodeItemListByIds(ids);
        return success(true);
    }

    /**
     * 获得码值
     *
     *  @param id 编号
     *  @return 码值
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据标准--码值")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<StandardCodeItemRespVO> getStandardCodeItem(@RequestParam("id") Integer id) {
        StandardCodeItemDO item = standardCodeItemService.getStandardCodeItem(id);
        var result = BeanUtils.toBean(item, StandardCodeItemRespVO.class);
        if (result != null) {
            catalog.fillItems(List.of(result));
        }
        return success(result);
    }

    /**
     * 获得码值分页
     *
     *  @param pageReqVO 分页查询
     *  @return 码值分页
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据标准--码值分页")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<PageResult<StandardCodeItemRespVO>> getStandardCodeItemPage(
            @Valid StandardCodeItemPageReqVO pageReqVO) {
        PageResult<StandardCodeItemDO> pageResult = standardCodeItemService.getStandardCodeItemPage(pageReqVO);
        var result = BeanUtils.toBean(pageResult, StandardCodeItemRespVO.class);
        catalog.fillItems(result.getList());
        return success(result);
    }

    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Integer id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = standardCodeItemService.getStandardCodeItem(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getLabel() == null
                        ? "编号" + id : auditBefore.getLabel());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
