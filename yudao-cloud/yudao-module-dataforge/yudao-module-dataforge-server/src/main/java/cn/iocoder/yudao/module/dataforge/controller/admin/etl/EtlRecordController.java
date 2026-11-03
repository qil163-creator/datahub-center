package cn.iocoder.yudao.module.dataforge.controller.admin.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlRecordPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlRecordRespVO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - ETL 流程日志")
@RestController
@RequestMapping("/dataforge/etl-record")
@Validated
public class EtlRecordController {

    @Resource
    private EtlPipelineService pipelineService;

    /**
     * 获得执行记录分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 执行记录分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得执行记录分页")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<PageResult<EtlRecordRespVO>> getRecordPage(@Valid EtlRecordPageReqVO pageReqVO) {
        return success(pipelineService.getRecordPage(pageReqVO));
    }

    /**
     * 删除执行记录（含节点记录）。
     *
     * @param id 记录编号
     * @return ETL 运行记录
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除执行记录（含节点记录）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:delete')")
    public CommonResult<Boolean> deleteRecord(@RequestParam("id") Long id) {
        pipelineService.deleteRecord(id);
        return success(true);
    }

}
