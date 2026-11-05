package cn.iocoder.yudao.module.governance.controller.admin.metadata;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagBindReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagBindingRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagSaveReqVO;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTagService;
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
 * 管理后台 - 元数据标签
 *
 * @author Li
 */
@Tag(name = "管理后台 - 元数据标签")
@RestController
@RequestMapping("/governance/meta-tag")
@Validated
public class MetaTagController {

    @Resource
    private MetaTagService tagService;

    /**
     * 创建元数据标签。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-元数据标签", subType = "创建元数据标签",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建元数据标签",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建元数据标签")
    @PreAuthorize("@ss.hasPermission('governance:meta-tag:create')")
    public CommonResult<Long> createTag(@Valid @RequestBody MetaTagSaveReqVO createReqVO) {
        return success(tagService.createTag(createReqVO));
    }

    /**
     * 更新元数据标签。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-元数据标签", subType = "更新元数据标签",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新元数据标签",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新元数据标签")
    @PreAuthorize("@ss.hasPermission('governance:meta-tag:update')")
    public CommonResult<Boolean> updateTag(@Valid @RequestBody MetaTagSaveReqVO updateReqVO) {
        tagService.updateTag(updateReqVO);
        return success(true);
    }

    /**
     * 删除元数据标签。
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-元数据标签", subType = "删除元数据标签",
            bizNo = "{{(#id) ?: 0}}", success = "删除元数据标签",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除元数据标签")
    @Parameter(name = "id", description = "标签编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:meta-tag:delete')")
    public CommonResult<Boolean> deleteTag(@RequestParam("id") Long id) {
        tagService.deleteTag(id);
        return success(true);
    }

    /**
     * 获得元数据标签。
     */
    @GetMapping("/get")
    @Operation(summary = "获得元数据标签")
    @PreAuthorize("@ss.hasPermission('governance:meta-tag:query')")
    public CommonResult<MetaTagRespVO> getTag(@RequestParam("id") Long id) {
        return success(tagService.getTag(id));
    }

    /**
     * 获得元数据标签分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得元数据标签分页")
    @PreAuthorize("@ss.hasPermission('governance:meta-tag:query')")
    public CommonResult<PageResult<MetaTagRespVO>> getTagPage(@Valid MetaTagPageReqVO pageReqVO) {
        return success(tagService.getTagPage(pageReqVO));
    }

    /**
     * 获得元数据标签精简列表。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得元数据标签精简列表")
    @PreAuthorize("@ss.hasPermission('governance:meta-tag:query')")
    public CommonResult<List<MetaTagRespVO>> getTagSimpleList() {
        return success(tagService.getTagSimpleList());
    }

    /**
     * 全量覆盖对象已绑标签
     */
    @PutMapping("/bind")
    @LogRecord(type = "数据治理-元数据标签", subType = "绑定标签（全量覆盖该对象已绑标签）",
            bizNo = "{{(#reqVO.objectId) ?: 0}}", success = "绑定标签（全量覆盖该对象已绑标签）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "绑定标签（全量覆盖该对象已绑标签）")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<Boolean> bindTags(@Valid @RequestBody MetaTagBindReqVO reqVO) {
        tagService.bindTags(reqVO);
        return success(true);
    }

    /**
     * 解绑单个标签。
     */
    @DeleteMapping("/unbind")
    @LogRecord(type = "数据治理-元数据标签", subType = "解绑单个标签",
            bizNo = "{{(#objectId) ?: 0}}", success = "解绑单个标签",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "解绑单个标签")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<Boolean> unbindTag(@RequestParam("tagId") Long tagId,
            @RequestParam("objectType") Integer objectType,
            @RequestParam("objectId") Long objectId,
            @RequestParam("revision") Integer revision) {
        tagService.unbindTag(tagId, objectType, objectId, revision);
        return success(true);
    }

    /**
     * 获得对象标签集合及修订号。
     */
    @GetMapping("/binding-snapshot")
    @Operation(summary = "获得对象标签集合及修订号")
    @PreAuthorize("@ss.hasPermission('governance:meta-tag:query')")
    public CommonResult<MetaTagBindingRespVO> getBindingSnapshot(@RequestParam("objectType") Integer objectType,
            @RequestParam("objectId") Long objectId) {
        return success(tagService.getBindingSnapshot(objectType, objectId));
    }

    /**
     * 获得对象已绑标签。
     */
    @GetMapping("/list-by-object")
    @Operation(summary = "获得对象已绑标签")
    @PreAuthorize("@ss.hasPermission('governance:meta-tag:query')")
    public CommonResult<List<MetaTagRespVO>> getTagsByObject(@RequestParam("objectType") Integer objectType,
            @RequestParam("objectId") Long objectId) {
        return success(tagService.getTagsByObject(objectType, objectId));
    }

}
