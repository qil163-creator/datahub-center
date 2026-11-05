package cn.iocoder.yudao.module.governance.controller.admin.metadata;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaObjectTreeNodeVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaPublishBatchRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaSearchHitVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaSearchReqVO;
import cn.iocoder.yudao.module.governance.service.metadata.MetaObjectService;
import jakarta.validation.Valid;
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
 * 管理后台 - 元数据对象（树与库级操作）
 *
 * @author Li
 */
@Tag(name = "管理后台 - 元数据对象")
@RestController
@RequestMapping("/governance/meta-object")
@Validated
public class MetaObjectController {

    @Resource
    private MetaObjectService objectService;

    /**
     * 元数据树：来源系统 → 数据源 → 库（含待确认删除对象，前端打警示标）
     */
    @GetMapping("/tree")
    @Operation(summary = "获得元数据树（来源系统 → 数据源 → 库）")
    @PreAuthorize("@ss.hasPermission('governance:meta-object:query')")
    public CommonResult<List<MetaObjectTreeNodeVO>> getObjectTree() {
        return success(objectService.getObjectTree());
    }

    /**
     * 确认删除库（级联其未删除的表与字段；记录与快照永久保留）
     */
    @PutMapping("/confirm-delete-db")
    @LogRecord(type = "数据治理-元数据对象", subType = "确认删除库（仅待确认删除状态；级联未删除的表与字段，历史与快照保留）",
            bizNo = "{{(#id) ?: 0}}", success = "确认删除了元数据库【{{(#id) ?: 0}}】及其待删除表字段，保留历史与快照",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "确认删除库（仅待确认删除状态；级联未删除的表与字段，历史与快照保留）")
    @Parameter(name = "id", description = "库元数据编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-object:update')")
    public CommonResult<Boolean> confirmDeleteDb(@RequestParam("id") Long id) {
        objectService.confirmDeleteDb(id);
        return success(true);
    }

    /**
     * 库级批量发布：发布本库全部「正常状态且校验通过且未发布」的表；
     *  已发布表跳过，所有定版指针保持不变
     */
    @PutMapping("/publish-db")
    @LogRecord(type = "数据治理-元数据对象", subType = "库级批量发布（发布本库全部正常且校验通过且未发布的表）",
            bizNo = "{{(#id) ?: 0}}", success = "提交了元数据库【{{(#id) ?: 0}}】的库级批量发布，处理结果见本次发布回执",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "库级批量发布（发布本库全部正常且校验通过且未发布的表）")
    @Parameter(name = "id", description = "库元数据编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:publish')")
    public CommonResult<MetaPublishBatchRespVO> publishDb(@RequestParam("id") Long id) {
        return success(objectService.publishDb(id));
    }

    /**
     * 元数据检索：关键字覆盖表名/字段名/业务名称/注释，支持分层/主题域/发布/认责过滤
     */
    @GetMapping("/search")
    @Operation(summary = "元数据检索（表名/字段名/业务名称/注释）")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<PageResult<MetaSearchHitVO>> search(@Valid MetaSearchReqVO reqVO) {
        return success(objectService.search(reqVO));
    }

}
