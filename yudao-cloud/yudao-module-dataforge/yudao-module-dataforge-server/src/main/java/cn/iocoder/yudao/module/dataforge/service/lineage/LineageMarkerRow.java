package cn.iocoder.yudao.module.dataforge.service.lineage;

import lombok.Data;

/**
 * 血缘上报候选：来源对象编号、当前修订号与已上报标记。
 * <p>
 * 只用于上报任务扫描，不映射为业务 DO，避免标记列进入任务定义、版本快照或整行更新。
 */
@Data
public class LineageMarkerRow {

    /** 来源对象编号。 */
    private Long id;
    /** 来源名称快照。 */
    private String sourceName;
    /** 当前修订号（开发任务为当前版本编号）；已删除对象为删除前的值。 */
    private Long sourceRevision;
    /** 已上报标记：{@code <修订号>:<解析器版本>}；未上报为 null。 */
    private String lineageReportKey;
    /** 来源对象是否已删除。 */
    private Boolean removed;

    /**
     * 是否已删除。
     */
    public boolean isRemovedSource() {
        return Boolean.TRUE.equals(removed);
    }
}
