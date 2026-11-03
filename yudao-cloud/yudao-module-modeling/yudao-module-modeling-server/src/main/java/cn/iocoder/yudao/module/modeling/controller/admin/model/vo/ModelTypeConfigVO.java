package cn.iocoder.yudao.module.modeling.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 表类型专属配置（当前模型共用）
 *
 * 五类表各有一段配置，只有与模型 tableType 对应的那段生效，其余为 null。
 * 字段引用统一用字段名（fieldName），与当前字段中的字段名一致。
 *
 * 这些配置回答的是「这张表到底是什么」：一行代表什么、按哪个时间算、数的是什么、重算怎么处理。
 * 没有它们，表类型只是一个标签。
 *
 * @author Li
 */
@Schema(description = "管理后台 - 表类型专属配置")
@Data
public class ModelTypeConfigVO {

    @Schema(description = "贴源表配置（tableType=SOURCE 时生效）")
    private SourceConfig source;

    @Schema(description = "维度表配置（tableType=DIMENSION 时生效）")
    private DimensionConfig dimension;

    @Schema(description = "明细表配置（tableType=DETAIL 时生效）")
    private DetailConfig detail;

    @Schema(description = "汇总表配置（tableType=SUMMARY 时生效）")
    private SummaryConfig summary;

    @Schema(description = "应用表配置（tableType=APPLICATION 时生效）")
    private ApplicationConfig application;

    // ==================== 贴源表 ====================

    @Schema(description = "贴源表配置")
    @Data
    public static class SourceConfig {

        @Schema(description = "来源系统", example = "政务服务审批系统")
        private String sourceSystem;

        @Schema(description = "来源表/接口", example = "t_accept_record")
        private String sourceTable;

        @Schema(description = "承接模式：CURRENT_STATE-当前状态 PERIODIC_SNAPSHOT-周期全量快照 CDC_EVENT-增量事件日志",
                example = "CURRENT_STATE")
        private String ingestMode;

        @Schema(description = "来源业务键字段（复合键按顺序）", example = "[\"source_system\",\"record_id\"]")
        private List<String> sourceKeyFields;

        @Schema(description = "事件序列字段（CDC_EVENT 模式必填）", example = "event_seq")
        private String eventSequenceField;

        @Schema(description = "删除处理：PHYSICAL-物理删除 LOGICAL_FLAG-逻辑标识 KEEP_EVENT-保留删除事件",
                example = "LOGICAL_FLAG")
        private String deleteStrategy;

        @Schema(description = "数据保留约定", example = "事件保留 3 年")
        private String retentionNote;

    }

    // ==================== 维度表 ====================

    @Schema(description = "维度表配置")
    @Data
    public static class DimensionConfig {

        @Schema(description = "业务键字段（复合键按顺序）", example = "[\"org_code\"]")
        private List<String> businessKeyFields;

        @Schema(description = "历史策略：FIXED-固定值 OVERWRITE-覆盖最新值 SCD2-保留历史版本",
                example = "SCD2")
        private String historyStrategy;

        @Schema(description = "生效时间字段（SCD2 必填）", example = "effective_from")
        private String effectiveFromField;

        @Schema(description = "失效时间字段（SCD2 必填）", example = "effective_to")
        private String effectiveToField;

        @Schema(description = "当前记录标识字段（SCD2 选填）", example = "is_current")
        private String currentFlagField;

        @Schema(description = "未知值处理：UNKNOWN_ROW-预置未知维度行 NULL-保留空值 REJECT-拒绝入库",
                example = "UNKNOWN_ROW")
        private String unknownValueStrategy;

    }

    // ==================== 明细表 ====================

    @Schema(description = "明细表配置")
    @Data
    public static class DetailConfig {

        @Schema(description = "组织方式：TRANSACTION-事务/事件明细 PERIODIC_SNAPSHOT-周期快照 ACCUMULATING_SNAPSHOT-累积快照",
                example = "TRANSACTION")
        private String organizationMode;

        @Schema(description = "粒度描述：一行代表什么", example = "一个去重后的受理事件的当前有效状态")
        private String grainDescription;

        @Schema(description = "构成粒度的字段", example = "[\"event_id\"]")
        private List<String> grainFields;

        @Schema(description = "业务时间字段", example = "accept_time")
        private String businessTimeField;

        @Schema(description = "去重依据字段", example = "[\"source_system\",\"event_id\"]")
        private List<String> dedupFields;

        @Schema(description = "迟到与更正处理：OVERWRITE-覆盖 APPEND_CORRECTION-追加更正记录 IGNORE-忽略",
                example = "OVERWRITE")
        private String lateArrivalStrategy;

    }

    // ==================== 汇总表 ====================

    @Schema(description = "汇总表配置")
    @Data
    public static class SummaryConfig {

        @Schema(description = "输出粒度字段（全部分组维度）", example = "[\"district_code\",\"accept_date\"]")
        private List<String> grainFields;

        @Schema(description = "时间粒度：DAY/WEEK/MONTH/QUARTER/YEAR/ROLLING_WINDOW/NONE", example = "DAY")
        private String timeGranularity;

        @Schema(description = "滚动窗口说明（ROLLING_WINDOW 时填写）", example = "近 7 日")
        private String rollingWindowNote;

        @Schema(description = "业务时间口径：以哪个时间划分周期", example = "受理时间 accept_time")
        private String businessTimeField;

        @Schema(description = "统计口径说明：数的是什么、如何去重、过滤了什么",
                example = "去重后的有效受理事件数，排除已撤销")
        private String measureDefinition;

        @Schema(description = "输入逻辑模型编号", example = "[12, 15]")
        private List<Long> inputModelIds;

        @Schema(description = "过滤条件", example = "status = 'VALID'")
        private String filterCondition;

        @Schema(description = "写入方式：OVERWRITE_RANGE-覆盖指定范围 UPSERT_BY_KEY-按键更新 APPEND_INCREMENT-事件增量累加",
                example = "OVERWRITE_RANGE")
        private String writeMode;

        @Schema(description = "重算时失效分组处理：DELETE-删除已消失分组 KEEP-保留 MARK-标记失效",
                example = "DELETE")
        private String staleGroupStrategy;

    }

    // ==================== 应用表 ====================

    @Schema(description = "应用表配置")
    @Data
    public static class ApplicationConfig {

        @Schema(description = "消费场景", example = "政务服务驾驶舱-区县受理概览")
        private String consumerScene;

        @Schema(description = "消费方（接口/报表/应用）", example = "[\"驾驶舱大屏\",\"API: /gov/accept/daily\"]")
        private List<String> consumers;

        @Schema(description = "输出粒度描述", example = "一个区县某日的展示记录")
        private String grainDescription;

        @Schema(description = "刷新要求：可接受的数据延迟与刷新方式", example = "T+1 日 08:00 前完成，全量覆盖")
        private String refreshRequirement;

        @Schema(description = "兼容约定：字段废弃、改名、口径变化如何通知消费方", example = "提前一周在服务契约中标注废弃")
        private String compatibilityNote;

    }

}
