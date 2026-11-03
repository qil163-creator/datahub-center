package cn.iocoder.yudao.module.governance.controller.admin.metadata;

import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file.*;
import cn.iocoder.yudao.module.governance.service.metadata.MetaFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 文件元数据")
@RestController
@RequestMapping("/governance/meta-file")
@Validated
public class MetaFileController {

    @Resource
    private MetaFileService service;

    /**
     * 获得文件元数据分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得文件元数据分页")
    @PreAuthorize("@ss.hasPermission('governance:meta-file:query')")
    public CommonResult<PageResult<MetaFileRespVO>> page(@Valid MetaFilePageReqVO req) {
        return success(service.page(req));
    }

    /**
     * 获得文件元数据详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得文件元数据详情")
    @PreAuthorize("@ss.hasPermission('governance:meta-file:query')")
    public CommonResult<MetaFileRespVO> get(@RequestParam("id") Long id) {
        return success(service.get(id));
    }

    /**
     * 获得已采集目录树。
     */
    @GetMapping("/tree")
    @Operation(summary = "获得已采集目录树")
    @PreAuthorize("@ss.hasPermission('governance:meta-file:query')")
    public CommonResult<List<MetaFileTreeNodeVO>> tree() {
        return success(service.tree());
    }

    /**
     * 更新文件业务属性。
     */
    @PutMapping("/update-biz")
    @Operation(summary = "更新文件业务属性")
    @PreAuthorize("@ss.hasPermission('governance:meta-file:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody MetaFileUpdateBizReqVO req) {
        service.updateBiz(req);
        return success(true);
    }

    /**
     * 删除文件元数据，不删除远端文件。
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除文件元数据，不删除远端文件")
    @PreAuthorize("@ss.hasPermission('governance:meta-file:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        service.delete(List.of(id));
        return success(true);
    }

    /**
     * 批量删除文件元数据，不删除远端文件。
     */
    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除文件元数据，不删除远端文件")
    @PreAuthorize("@ss.hasPermission('governance:meta-file:delete')")
    public CommonResult<Boolean> deleteList(@RequestParam("ids") List<Long> ids) {
        service.delete(ids);
        return success(true);
    }
}
