package cn.iocoder.yudao.module.asset.controller.admin.dataquery;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryFileRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryFileSaveReqVO;
import cn.iocoder.yudao.module.asset.service.dataquery.DataQueryFileService;
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

@Tag(name = "管理后台 - 数据查询文件目录")
@RestController
@RequestMapping("/asset/data-query/file")
@Validated
public class DataQueryFileController {

    @Resource
    private DataQueryFileService dataQueryFileService;

    /**
     * 创建目录或 SQL 脚本。
     */
    @LogRecord(type = "数据资产-数据查询文件目录", subType = "创建目录或 SQL 脚本", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建目录或 SQL 脚本", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建目录或 SQL 脚本")
    @PreAuthorize("@ss.hasPermission('asset:data-query:save')")
    public CommonResult<Long> createFile(@Valid @RequestBody DataQueryFileSaveReqVO reqVO) {
        return success(dataQueryFileService.createFile(reqVO));
    }

    /**
     * 更新目录或 SQL 脚本。
     */
    @LogRecord(type = "数据资产-数据查询文件目录", subType = "更新目录或 SQL 脚本", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "更新目录或 SQL 脚本", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新目录或 SQL 脚本")
    @PreAuthorize("@ss.hasPermission('asset:data-query:save')")
    public CommonResult<Boolean> updateFile(@Valid @RequestBody DataQueryFileSaveReqVO reqVO) {
        dataQueryFileService.updateFile(reqVO);
        return success(true);
    }

    /**
     * 删除目录或 SQL 脚本。
     */
    @LogRecord(type = "数据资产-数据查询文件目录", subType = "删除目录或 SQL 脚本", bizNo = "{{(#id) ?: 0}}",
            success = "删除目录或 SQL 脚本", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除目录或 SQL 脚本")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('asset:data-query:delete')")
    public CommonResult<Boolean> deleteFile(@RequestParam("id") Long id) {
        dataQueryFileService.deleteFile(id);
        return success(true);
    }

    /**
     * 获得目录或 SQL 脚本详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得目录或 SQL 脚本详情")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('asset:data-query:query')")
    public CommonResult<DataQueryFileRespVO> getFile(@RequestParam("id") Long id) {
        return success(dataQueryFileService.getFile(id));
    }

    /**
     * 获得目录树扁平列表，不含 SQL 正文。
     */
    @GetMapping("/list")
    @Operation(summary = "获得文件目录扁平列表（前端组装树）")
    @PreAuthorize("@ss.hasPermission('asset:data-query:query')")
    public CommonResult<List<DataQueryFileRespVO>> getFileList() {
        return success(dataQueryFileService.getFileList());
    }

}
