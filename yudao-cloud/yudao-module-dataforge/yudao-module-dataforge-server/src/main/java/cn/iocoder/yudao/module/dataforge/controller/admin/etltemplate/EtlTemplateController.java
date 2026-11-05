package cn.iocoder.yudao.module.dataforge.controller.admin.etltemplate;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.etltemplate.vo.*;
import cn.iocoder.yudao.module.dataforge.service.etltemplate.EtlTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - ETL 团队模板")
@RestController
@RequestMapping("/dataforge/etl-template")
@Validated
public class EtlTemplateController {

    @Resource
    private EtlTemplateService service;

    /**
     * 创建团队图片段模板，不复制运行身份或参数默认值。
     *
     * @param request 请求参数
     * @return ETL模板
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-ETL 团队模板", subType = "创建团队图片段模板，不复制运行身份或参数默认值", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了 ETL 团队模板【{{(#request.name) ?: '未设置'}}】，节点数【{{(#request.nodes.size()) ?: '未设置'}}】，连线数【{{(#request.edges.size()) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建团队图片段模板，不复制运行身份或参数默认值")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save') and @ss.hasPermission('dataforge:etl:query')")
    public CommonResult<Long> create(@Valid @RequestBody EtlTemplateCreateReqVO request) {
        return success(service.create(request));
    }

    /**
     * 分页查看本平台团队模板元数据。
     *
     * @param request 请求参数
     * @return 分页查看本平台团队模板元数据结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查看本平台团队模板元数据")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<PageResult<EtlTemplateRespVO>> page(@Valid EtlTemplatePageReqVO request) {
        return success(service.page(request));
    }

    /**
     * 授权读取模板图片段，应用前须重新读取。
     *
     * @param id 记录编号
     * @return ETL模板响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "授权读取模板图片段，应用前须重新读取")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlTemplateRespVO> get(@RequestParam("id") Long id) {
        return success(service.get(id));
    }

    /**
     * 创建人删除团队模板，不影响已应用的流程。
     *
     * @param id 记录编号
     * @return ETL模板
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-ETL 团队模板", subType = "创建人删除团队模板，不影响已应用的流程", bizNo = "{{(#id) ?: 0}}",
            success = "删除了 ETL 团队模板【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】），保留已应用到流程的配置", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建人删除团队模板，不影响已应用的流程")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = service.get(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        service.delete(id);
        return success(true);
    }
}
