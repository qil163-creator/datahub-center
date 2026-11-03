package cn.iocoder.yudao.module.modeling.controller.admin.model;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.*;
import cn.iocoder.yudao.module.modeling.service.model.ModelMaterializedService;
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

@Tag(name = "管理后台 - 模型物化发布")
@RestController
@RequestMapping("/modeling/model-materialized")
@Validated
public class ModelMaterializedController {

    @Resource
    private ModelMaterializedService modelMaterializedService;

    /**
     * 物化发布分页。
     *
     * @param reqVO 请求参数
     * @return 物化发布分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "物化发布分页")
    @PreAuthorize("@ss.hasPermission('modeling:model-materialized:query')")
    public CommonResult<PageResult<ModelMaterializedRespVO>> getPage(@Valid ModelMaterializedPageReqVO reqVO) {
        return success(modelMaterializedService.getPage(reqVO));
    }

    /**
     * 发布模型详情（按模型编号可查看尚未物化的模型）。
     *
     * @param id 记录编号
     * @param modelId 模型编号
     * @return 模型物化响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "发布模型详情（按模型编号可查看尚未物化的模型）")
    @Parameter(name = "id", description = "物化记录编号；与 modelId 二选一")
    @Parameter(name = "modelId", description = "逻辑模型编号；与 id 二选一")
    @PreAuthorize("@ss.hasPermission('modeling:model-materialized:query')")
    public CommonResult<ModelMaterializedRespVO> get(@RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "modelId", required = false) Long modelId) {
        if ((id == null) == (modelId == null)) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException(
                    "id 和 modelId 必须且只能提供一个");
        }
        if (modelId != null) {
            return success(modelMaterializedService.getByModelId(modelId));
        }
        return success(modelMaterializedService.get(id));
    }

    /**
     * 获得模型发布数据源选项（不含凭据）。
     *
     * @return 数据源列表
     */
    @GetMapping("/database-list")
    @Operation(summary = "获得模型发布数据源选项（不含凭据）")
    @PreAuthorize("@ss.hasPermission('modeling:model-materialized:query')")
    public CommonResult<List<ModelMaterializedDatabaseRespVO>> getDatabaseList() {
        return success(modelMaterializedService.getDatabaseList());
    }

    /**
     * 获得发布模型分类目录。
     *
     * @return 分类树
     */
    @GetMapping("/cat-tree")
    @Operation(summary = "获得发布模型分类目录")
    @PreAuthorize("@ss.hasPermission('modeling:model-materialized:query')")
    public CommonResult<List<ModelMaterializedCatNodeRespVO>> getCatTree() {
        return success(modelMaterializedService.getCatTree());
    }

    /**
     * 发布日志分页。
     *
     * @param reqVO 请求参数
     * @return 发布日志分页结果
     */
    @GetMapping("/log-page")
    @Operation(summary = "发布日志分页")
    @PreAuthorize("@ss.hasPermission('modeling:model-materialized:query')")
    public CommonResult<PageResult<ModelMaterializedLogRespVO>> getLogPage(@Valid ModelMaterializedLogPageReqVO reqVO) {
        return success(modelMaterializedService.getLogPage(reqVO));
    }

    /**
     * 首次发布模型。
     *
     * @param reqVO 请求参数
     * @return 模型物化
     */
    @PostMapping("/publish")
    @Operation(summary = "首次发布模型")
    @PreAuthorize("@ss.hasPermission('modeling:model-materialized:publish')")
    public CommonResult<Long> publish(@Valid @RequestBody ModelMaterializedPublishReqVO reqVO) {
        return success(modelMaterializedService.publish(reqVO));
    }

    /**
     * 重新发布模型。
     *
     * @param reqVO 请求参数
     * @return 模型物化
     */
    @PostMapping("/republish")
    @Operation(summary = "重新发布模型")
    @PreAuthorize("@ss.hasPermission('modeling:model-materialized:publish')")
    public CommonResult<Long> republish(@Valid @RequestBody ModelMaterializedRepublishReqVO reqVO) {
        return success(modelMaterializedService.republish(reqVO));
    }

    /**
     * 对账未知部署或恢复已过期执行；不执行新的DDL。
     *
     * @param id 记录编号
     * @return 模型物化
     */
    @PostMapping("/reconcile")
    @Operation(summary = "对账未知部署或恢复已过期执行；不执行新的DDL")
    @PreAuthorize("@ss.hasPermission('modeling:model-materialized:publish')")
    public CommonResult<String> reconcile(@RequestParam("id") Long id) {
        return success(modelMaterializedService.reconcile(id));
    }

}
