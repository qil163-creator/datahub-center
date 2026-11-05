package cn.iocoder.yudao.module.dataforge.controller.admin.dev;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir.DevDirRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir.DevDirSaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.dev.DevDirService;
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
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

/**
 * 数据开发-任务目录 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据开发目录")
@RestController
@RequestMapping("/dataforge/dev-dir")
@Validated
public class DevDirController {

    @Resource
    private DevDirService dirService;

    /**
     * 创建开发目录。
     *
     * @param createReqVO 创建参数
     * @return 开发目录
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-数据开发目录", subType = "创建开发目录", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建开发目录", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建开发目录")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:save')")
    public CommonResult<Long> createDir(@Valid @RequestBody DevDirSaveReqVO createReqVO) {
        return success(dirService.createDir(createReqVO));
    }

    /**
     * 更新开发目录。
     *
     * @param updateReqVO 更新参数
     * @return 开发目录
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-数据开发目录", subType = "更新开发目录", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "更新开发目录", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新开发目录")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:save')")
    public CommonResult<Boolean> updateDir(@Valid @RequestBody DevDirSaveReqVO updateReqVO) {
        dirService.updateDir(updateReqVO);
        return success(true);
    }

    /**
     * 删除开发目录。
     *
     * @param id 记录编号
     * @return 开发目录
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-数据开发目录", subType = "删除开发目录", bizNo = "{{(#id) ?: 0}}",
            success = "删除开发目录", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除开发目录")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:delete')")
    public CommonResult<Boolean> deleteDir(@RequestParam("id") Long id) {
        dirService.deleteDir(id);
        return success(true);
    }

    /**
     * 获得开发目录列表。
     *
     * @return 目录列表
     */
    @GetMapping("/list")
    @Operation(summary = "获得开发目录列表")
    @PreAuthorize("@ss.hasPermission('dataforge:dev-task:query')")
    public CommonResult<List<DevDirRespVO>> getDirList() {
        return success(convertList(dirService.getDirList(),
                dir -> BeanUtils.toBean(dir, DevDirRespVO.class)));
    }

}
