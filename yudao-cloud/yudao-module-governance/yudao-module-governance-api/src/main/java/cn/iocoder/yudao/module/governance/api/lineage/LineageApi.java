package cn.iocoder.yudao.module.governance.api.lineage;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 数据血缘上报接口：governance 是血缘持久化与查询的唯一 owner，生产者（dataforge）只能经此契约写入。
 *
 * <p>语义：按 (sourceType, sourceId) 整体替换该来源的全部表级与字段级边；按 (sourceRevision, analyzerVersion)
 * 字典序判定新旧，落后返回 STALE、修订与内容摘要都未变返回 NOOP，均不改动数据；status=REMOVED 时不比较修订，
 * 直接清空该来源的边。调用是幂等的，生产者可在超时或未知结果时原样重试。</p>
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 数据血缘")
public interface LineageApi {

    String PREFIX = ApiConstants.PREFIX + "/lineage";

    /**
     * 上报一个来源对象的完整血缘（整体替换）。
     */
    @PostMapping(PREFIX + "/report")
    @Operation(summary = "上报一个来源对象的完整血缘（整体替换）")
    CommonResult<LineageReportRespDTO> report(@Valid @RequestBody LineageReportReqDTO req);

}
