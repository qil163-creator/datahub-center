package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-周期任务 DO（计划在某个数据所属期的实际任务；一次性任务也是一条）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_task")
@KeySequence("dh_dataforge_fill_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillTaskDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 采集计划编号
     */
    private Long planId;

    /**
     * 任务名称（含期次，如 XX 月报 2026-08）
     */
    private String name;

    /**
     * 模板主表编号
     */
    private Long tableId;

    /**
     * 冻结的模板版本编号
     */
    private Long tableVersionId;

    /**
     * BPM 流程定义 Key
     */
    private String processDefinitionKey;

    /**
     * 任务发布时冻结的 BPM 流程定义版本 ID，后续流程升级不影响本任务
     */
    private String processDefinitionId;

    /**
     * 数据所属期：如 2026-08-31 / 2026-08 / 2026-W35 / 2026-Q3 / ONCE-{planId}
     */
    private String periodKey;

    /**
     * 所属期开始时间
     */
    private LocalDateTime periodStart;

    /**
     * 所属期结束时间
     */
    private LocalDateTime periodEnd;

    /**
     * 填报开放时间
     */
    private LocalDateTime openTime;

    /**
     * 填报截止时间
     */
    private LocalDateTime deadlineTime;

    /**
     * 逾期策略：1-软逾期 2-硬截止，见 FillOverduePolicyEnum
     */
    private Integer overduePolicy;

    /**
     * 状态：0-待开始 1-进行中 2-已完成 3-已取消，见 FillTaskStatusEnum
     */
    private Integer status;

    /**
     * 应填组织总数（进度冗余）
     */
    private Integer totalOrgCount;

    /**
     * 已合并组织数（进度冗余）
     */
    private Integer mergedOrgCount;

}
