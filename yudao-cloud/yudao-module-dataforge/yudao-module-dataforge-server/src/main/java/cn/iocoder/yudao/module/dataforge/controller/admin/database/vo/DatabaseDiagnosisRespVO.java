package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 数据库连接分组诊断 Response VO")
@Data
public class DatabaseDiagnosisRespVO {

    private String diagnosisId;

    @Schema(description = "总体检查状态", allowableValues = {"PASS", "WARN", "FAIL", "SKIPPED"})
    private String overallStatus;

    @Schema(description = "真实连接及目标命名空间验证成功，与可选写入检查状态独立")
    private boolean connectionSuccessful;

    @Schema(description = "诊断开始时间，序列化为毫秒时间戳")
    private LocalDateTime checkedAt;

    private long durationMs;

    private List<Group> groups;

    private int normalCount;

    private int warningCount;

    private int errorCount;

    private int skippedCount;

    private int totalCount;

    @Data
    public static class Counts {

        private int normalCount;

        private int warningCount;

        private int errorCount;

        private int skippedCount;

        private int totalCount;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Group extends Counts {

        private String groupCode;

        private String groupName;

        private String description;

        private String status;

        private List<Item> items;
    }

    @Data
    public static class Item {

        private String checkCode;

        private String checkName;

        private String checkContent;

        private String status;

        private String message;

        @Schema(description = "未执行原因；不从驱动错误文案推断产品能力", allowableValues = {
                "NOT_APPLICABLE", "NOT_AUTHORIZED", "PREREQUISITE_FAILED", "NOT_IMPLEMENTED", "NO_SAMPLE",
                "BUDGET_EXHAUSTED", "UNVERIFIED"})
        private String reasonCode;

        private String impact;

        private long durationMs;
    }
}
