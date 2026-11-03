package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 主数据派发任务 DO
 *
 * 参考 srt-cloud 的 data_governance_master_distribute：
 * 把已发布主数据模型的物理表数据推送到外部数据库 / HTTP 接口 / 消息队列
 *
 * @author Li
 */
@TableName(value = "dh_governance_master_distribute", autoResultMap = true)
@KeySequence("dh_governance_master_distribute_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterDistributeDO extends BaseDO {

    /** 请求认证与自定义头参数统一加密；禁止通过响应 VO 暴露。 */
    @TableField(typeHandler = cn.iocoder.yudao.framework.mybatis.core.type.EncryptTypeHandler.class)
    @ToString.Exclude
    private String requestSecrets;

    private Long scheduleGeneration;

    public static final int STATUS_UNRELEASED = 0;

    public static final int STATUS_RELEASED = 1;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 主数据模型编号
     */
    private Long masterModelId;

    /** 主数据推送类目，历史任务可为空。 */
    private Long categoryId;

    /**
     * 派发名称
     */
    private String name;

    /**
     * 派发类型：1-数据库 2-API 3-消息队列
     *
     * 枚举 {@link cn.iocoder.yudao.module.governance.enums.master.MasterDistributeTypeEnum}
     */
    private Integer distributeType;

    /**
     * 派发配置 JSON（按类型只保留对应节点：db / api / mq）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String distributeJson;

    /**
     * 发布状态：0-未发布 1-已发布
     */
    private Integer status;

    /**
     * 调度类型：1-一次性 2-周期性
     *
     * 枚举 {@link cn.iocoder.yudao.module.governance.enums.master.MasterTaskTypeEnum}
     */
    private Integer taskType;

    /**
     * Cron 表达式（周期任务）
     */
    private String cron;

    /**
     * 下次执行时间（周期任务调度用）
     */
    private LocalDateTime nextRunTime;

    /**
     * 描述
     */
    private String description;

    /**
     * 最近发布时间，取消发布后保留，再次发布时覆盖
     */
    private LocalDateTime releaseTime;

    /**
     * 最近发布人编号，取消发布后保留，再次发布时覆盖
     */
    private Long releaseUserId;

    /**
     * 所属机构编号
     */
    private Long orgId;

}
