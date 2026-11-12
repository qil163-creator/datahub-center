package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;

import java.util.List;

/**
 * 一类血缘来源（开发任务、数据同步、集成任务、ETL 流程）的采集适配器。
 * <p>
 * 实现只读取本模块的数据并构造上报内容，不调用 governance 的写接口，不在数据库锁事务中执行远程调用。
 */
public interface LineageSourceCollector {

    /**
     * 来源类型，取值见 governance 的 {@code LineageSourceTypeEnum}。
     */
    String sourceType();

    /**
     * 当前解析器版本。
     */
    int analyzerVersion();

    /**
     * 有界扫描待上报的来源：标记与当前修订或解析器版本不一致的对象，以及已删除但仍有标记的对象。
     */
    List<LineageMarkerRow> pending(int limit);

    /**
     * 构造上报内容；已删除的来源返回 REMOVED。
     */
    LineageReportReqDTO build(LineageMarkerRow row);

    /**
     * 上报成功后按旧标记做 CAS 更新；返回 false 表示标记已被并发修改，下一轮会重新判断。
     *
     * @param newKey 新标记；来源已删除时为 null
     */
    boolean mark(LineageMarkerRow row, String newKey);
}
