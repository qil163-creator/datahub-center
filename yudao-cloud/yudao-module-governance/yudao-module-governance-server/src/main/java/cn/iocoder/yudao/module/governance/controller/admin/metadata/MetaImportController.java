package cn.iocoder.yudao.module.governance.controller.admin.metadata;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaImportRowVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaTableRegisterReqVO;
import cn.iocoder.yudao.module.governance.service.metadata.MetaImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 元数据导入与手工登记（政务无直连报送通道）
 *
 * @author Li
 */
@Tag(name = "管理后台 - 元数据导入")
@RestController
@RequestMapping("/governance/meta-import")
@Validated
public class MetaImportController {

    @Resource
    private MetaImportService importService;

    /**
     * 导入 Excel 数据字典（走与直连采集同一套 diff 引擎）。
     */
    @PostMapping("/excel")
    @LogRecord(type = "数据治理-元数据导入", subType = "导入元数据字典（处理结果见导入回执）",
            bizNo = "{{(#datasourceId) ?: 0}}", success = "导入元数据字典（处理结果见导入回执）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "导入 Excel 数据字典（走与直连采集同一套 diff 引擎）")
    @Parameter(name = "datasourceId", description = "数据源编号（无直连场景登记逻辑数据源）", required = true)
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<MetaImportRespVO> importExcel(
            @RequestParam("datasourceId") Long datasourceId,
            @RequestParam(value = "sourceSystemId", required = false) Long sourceSystemId,
            @RequestParam(value = "sourceSystemName", required = false) String sourceSystemName,
            @RequestParam("file") MultipartFile file) throws Exception {
        return success(importService.importDict(datasourceId, sourceSystemId, sourceSystemName, file));
    }

    /**
     * 下载数据字典导入模板。
     */
    @GetMapping("/template")
    @Operation(summary = "下载数据字典导入模板")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        MetaImportRowVO sample1 = new MetaImportRowVO();
        sample1.setDbName("ods");
        sample1.setTableName("ods_person");
        sample1.setTableComment("人口基础信息表");
        sample1.setColumnName("id_card");
        sample1.setColumnComment("身份证号");
        sample1.setDataType("varchar");
        sample1.setDataLength(18L);
        sample1.setNullableText("否");
        sample1.setPkText("是");
        sample1.setAutoIncrementText("否");
        MetaImportRowVO sample2 = new MetaImportRowVO();
        sample2.setDbName("ods");
        sample2.setTableName("ods_person");
        sample2.setTableComment("人口基础信息表");
        sample2.setColumnName("name");
        sample2.setColumnComment("姓名");
        sample2.setDataType("varchar");
        sample2.setDataLength(64L);
        sample2.setNullableText("是");
        sample2.setPkText("否");
        sample2.setAutoIncrementText("否");
        ExcelUtils.write(response, "元数据数据字典导入模板.xlsx", "数据字典",
                MetaImportRowVO.class, List.of(sample1, sample2));
    }

    /**
     * 手工登记单张表
     *
     *  @return 表元数据编号
     */
    @PostMapping("/register-table")
    @LogRecord(type = "数据治理-元数据导入", subType = "手工登记单张表（origin=手工）",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "手工登记单张表（origin=手工）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "手工登记单张表（origin=手工）")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<Long> registerTable(@Valid @RequestBody MetaTableRegisterReqVO reqVO) {
        return success(importService.registerTable(reqVO));
    }

}
