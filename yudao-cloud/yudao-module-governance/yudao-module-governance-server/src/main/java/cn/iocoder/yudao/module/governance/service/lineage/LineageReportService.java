package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;

/**
 * 血缘上报：按来源整体替换表级与字段级边。
 */
public interface LineageReportService {

    /**
     * 接收一个来源对象的完整血缘报告。
     *
     * <ul>
     *     <li>按 (sourceRevision, analyzerVersion) 字典序与已存值比较：小于返回 STALE；相等且 contentHash 相同返回 NOOP；
     *     否则删除该来源全部边并写入新边，返回 ACCEPTED。</li>
     *     <li>status=REMOVED 不比较修订：清空边、标记 REMOVED，已存修订取两者较大值；已是 REMOVED 且修订未前进时返回 NOOP。</li>
     *     <li>已标记 REMOVED 的来源只接受修订严格更大的非 REMOVED 报告，相等返回 STALE。</li>
     * </ul>
     *
     * @throws cn.iocoder.yudao.framework.common.exception.ServiceException LINEAGE_REPORT_INVALID 报告不合法
     */
    LineageReportRespDTO report(LineageReportReqDTO req);

}
