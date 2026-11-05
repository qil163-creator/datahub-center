package cn.iocoder.yudao.module.governance.controller.admin.quality;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
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
    @LogRecord(type = "数据治理-数据质量-检查对象", subType = "创建检查对象",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了质量检查对象【{{(#createReqVO.objectName) ?: '未设置'}}】，编码【{{(#createReqVO.tableName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建检查对象")
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:create')")
    public CommonResult<Long> createCheckObject(@Valid @RequestBody QualityCheckObjectSaveReqVO createReqVO) {
        return success(checkObjectService.createCheckObject(createReqVO));
    }

    /**
     * 更新检查对象。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-数据质量-检查对象", subType = "更新检查对象",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了质量检查对象，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.objectName) ?: '未设置'}}】，提交编码【{{(#updateReqVO.tableName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新检查对象")
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:update')")
    public CommonResult<Boolean> updateCheckObject(@Valid @RequestBody QualityCheckObjectSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        checkObjectService.updateCheckObject(updateReqVO);
        return success(true);
    }

    /**
     * 删除检查对象。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-数据质量-检查对象", subType = "删除检查对象",
            bizNo = "{{(#id) ?: 0}}", success = "删除了质量检查对象【{{(#auditObjectName) ?: '编号' + #id}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除检查对象")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:quality-check-object:delete')")
    public CommonResult<Boolean> deleteCheckObject(@RequestParam("id") Long id) {
        captureAuditContext(id);
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


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = checkObjectService.getCheckObject(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getObjectName() == null
                        ? "编号" + id : auditBefore.getObjectName());
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
