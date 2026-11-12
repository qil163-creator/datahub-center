package cn.iocoder.yudao.module.governance.api.lineage;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;
import cn.iocoder.yudao.module.governance.service.lineage.LineageReportService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据血缘 RPC 接口实现：只做协议适配，校验、替换语义与事务都在 {@link LineageReportService}。
 */
@RestController
@Validated
public class LineageApiImpl implements LineageApi {

    @Resource
    private LineageReportService reportService;

    @Override
    public CommonResult<LineageReportRespDTO> report(LineageReportReqDTO req) {
        return success(reportService.report(req));
    }

}
