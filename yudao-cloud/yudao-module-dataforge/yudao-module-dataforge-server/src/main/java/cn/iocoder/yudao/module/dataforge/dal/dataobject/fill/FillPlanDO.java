package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-采集计划 DO（周期规则与任务生成模板；creator 即任务发起者）
 *
 * 按业务记录执行后台任务，不依赖租户上下文。
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_fill_plan", autoResultMap = true)
@KeySequence("dh_dataforge_fill_plan_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillPlanDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 计划名称
     */
    private String name;

    /**
     * 计划分类编号，空值表示未分类
     */
    private Long categoryId;

    /**
     * 业务说明
     */
    private String description;

    /**
     * 模板主表编号
     */
    private Long tableId;

    /**
     * 绑定的模板版本编号（不可变引用）
     */
    private Long tableVersionId;

    /**
     * 周期类型：1-一次性 2-每日 3-每周 4-每月 5-每季度，见 FillPeriodTypeEnum
     */
    private Integer periodType;

    /**
     * 周期参数 JSON：周几/生成日/开放与截止偏移/一次性任务起止时间等
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String periodConfig;

    /**
     * 时区，默认 Asia/Shanghai
     */
    private String timeZone;

    /**
     * 提醒规则 JSON：临近截止提前量、催办频控等
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String remindConfig;

    /**
     * 逾期策略：1-软逾期 2-硬截止，见 FillOverduePolicyEnum
     */
    private Integer overduePolicy;

    /**
     * BPM 流程定义 Key（任务发布时冻结具体 processDefinitionId）
     */
    private String processDefinitionKey;

    /**
     * 任务发布审批开关：0-关 1-开
     */
    private Integer publishApprovalEnabled;

    /**
     * 上期数据带入：0-关 1-开
     */
    private Integer prefillLastPeriod;

    /**
     * 发起单位编号
     */
    private Long ownerOrgId;

    /**
     * 状态：0-草稿 1-启用 2-停用，见 FillPlanStatusEnum
     */
    private Integer status;

    /**
     * 最近一次已生成任务的所属期（生成位点，用于停机追赶补生成）
     */
    private String lastPeriodKey;

    /**
     * 下次生成时间（生成 Job 扫描 + CAS 抢占）
     */
    private LocalDateTime nextGenerateTime;

}
