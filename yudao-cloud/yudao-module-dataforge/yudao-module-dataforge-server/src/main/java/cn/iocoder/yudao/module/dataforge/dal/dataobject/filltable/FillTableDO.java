package cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据填报-填报表定义 DO
 *
 * 元数据表：字段设计保存在 {@link FillTableColumnDO}，
 * 发布时在绑定数据源的 schema 下创建同名物理表，取消发布时删除物理表
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_table")
@KeySequence("dh_dataforge_fill_table_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillTableDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 物理表英文名
     */
    private String tableEngName;

    /**
     * 表中文名（发布时作为表注释）
     */
    private String tableCnName;

    /**
     * 描述
     */
    private String description;

    /**
     * 目标数据源id（物理表建在该数据源的 schema 下）
     */
    private Long databaseId;

    /**
     * 发布状态：0-未发布 1-已发布
     */
    private Integer status;

    /**
     * 发布人id
     */
    private Long releaseUserId;

    /**
     * 发布时间
     */
    private LocalDateTime releaseTime;

    /**
     * 单位id
     */
    private Long orgId;

    /**
     * 工作模式：1-遗留直写（旧链路，仅限存量模板受控使用） 2-审批流（新链路，禁止直写正式表），
     * 见 FillWorkflowModeEnum
     */
    private Integer workflowMode;

    /**
     * 当前已发布模板版本编号（dh_dataforge_fill_table_version.id）
     */
    private Long currentVersionId;

    /**
     * 合并策略：APPEND/UPSERT/REPLACE_SCOPE，空值按 REPLACE_SCOPE，见 FillMergeStrategyEnum；发布时固化进模板版本
     */
    private String mergeStrategy;

    /**
     * UPSERT 业务键 fieldCode 列表（英文逗号分隔）；组织与所属期由系统自动并入业务键
     */
    private String businessKeyFields;

    /**
     * 录入页面冻结的前几列（0 表示不冻结）
     */
    private Integer freezeColumnCount;

    /**
     * 多级汇总方式：DETAIL-明细汇集 GROUP-分组合计
     */
    private String rollupMode;

    /**
     * 分组合计的分组字段码，英文逗号分隔
     */
    private String rollupGroupFields;

}
