package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.module.governance.api.lineage.LineageApi;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 血缘上报编排：扫描各来源的待上报对象，构造内容并调用 governance，成功后 CAS 更新标记。
 * <p>
 * 可靠性：governance 按（来源修订号, 解析器版本）整体替换并拒绝旧报告，重复、超时重试、乱序到达都安全；
 * 标记只在上报得到明确结果后更新，RPC 失败保持原标记由下一轮重试。上报内容被 governance 拒绝（非瞬时错误）时
 * 改为上报一条只含原因的 FAILED 报告，避免同一内容每轮重复失败。不在数据库事务中执行远程调用。
 */
@Slf4j
@Service
public class LineageReportService {

    /** 每类来源每轮最多处理的对象数。 */
    public static final int DEFAULT_BATCH = 50;

    @Resource
    private List<LineageSourceCollector> collectors;
    @Resource
    private LineageApi lineageApi;

    /**
     * 处理一轮待上报对象，返回摘要。
     */
    public String reportPending(int batch) {
        StringBuilder summary = new StringBuilder();
        for (LineageSourceCollector collector : collectors) {
            int reported = 0;
            int failed = 0;
            for (LineageMarkerRow row : collector.pending(batch)) {
                if (reportOne(collector, row)) {
                    reported++;
                } else {
                    failed++;
                }
            }
            if (!summary.isEmpty()) {
                summary.append("；");
            }
            summary.append(collector.sourceType()).append(" 上报 ").append(reported).append("，待重试 ").append(failed);
        }
        return summary.toString();
    }

    /**
     * 上报单个来源；返回是否得到明确结果并更新了标记。
     */
    boolean reportOne(LineageSourceCollector collector, LineageMarkerRow row) {
        LineageReportReqDTO report;
        try {
            report = collector.build(row);
        } catch (RuntimeException analysisError) {
            // 只记录异常类型：解析或读取异常可能包含脚本、连接或参数内容。
            log.warn("[reportOne][构造血缘失败 type={} id={} failureType={}]", collector.sourceType(), row.getId(),
                    analysisError.getClass().getSimpleName());
            if (row.isRemovedSource()) {
                return false;
            }
            report = new LineageReportBuilder(collector.sourceType(), row.getId(), row.getSourceName(),
                    row.getSourceRevision() == null ? 0L : row.getSourceRevision(), collector.analyzerVersion())
                    .status(SqlLineageStatus.FAILED).issue("ANALYSIS_ERROR", "血缘解析失败", null).build();
        }
        if (!send(collector, row, report)) {
            return false;
        }
        String newKey = row.isRemovedSource() ? null
                : LineageReportVersions.key(report.getSourceRevision(), report.getAnalyzerVersion());
        if (!collector.mark(row, newKey)) {
            log.info("[reportOne][标记已被并发修改，下一轮重新判断 type={} id={}]", collector.sourceType(), row.getId());
        }
        return true;
    }

    /**
     * 调用 governance；业务拒绝时改为上报失败原因。
     */
    private boolean send(LineageSourceCollector collector, LineageMarkerRow row, LineageReportReqDTO report) {
        try {
            LineageReportRespDTO resp = lineageApi.report(report).getCheckedData();
            log.debug("[send][血缘上报完成 type={} id={} result={}]", collector.sourceType(), row.getId(),
                    resp == null ? null : resp.getResult());
            return true;
        } catch (ServiceException rejected) {
            log.warn("[send][血缘上报被拒绝 type={} id={} code={}]", collector.sourceType(), row.getId(),
                    rejected.getCode());
            if (row.isRemovedSource()) {
                return false;
            }
            try {
                lineageApi.report(LineageReportBuilder.rejected(report, rejected.getMessage())).getCheckedData();
                return true;
            } catch (RuntimeException again) {
                log.warn("[send][失败原因上报未成功 type={} id={} failureType={}]", collector.sourceType(),
                        row.getId(), again.getClass().getSimpleName());
                return false;
            }
        } catch (RuntimeException unavailable) {
            log.warn("[send][血缘上报暂不可用，等待重试 type={} id={} failureType={}]", collector.sourceType(),
                    row.getId(), unavailable.getClass().getSimpleName());
            return false;
        }
    }
}
