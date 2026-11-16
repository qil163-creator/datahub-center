package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 主数据疑似重复组")
@Data
public class MasterMatchGroupRespVO {

    private Long id;

    private Long runId;

    private Long modelId;

    @Schema(description = "状态：PENDING 待处理、MERGING 合并审批中、MERGED 已合并、IGNORED 已忽略、STALE 已被新一次匹配取代")
    private String status;

    private Integer memberCount;

    @Schema(description = "组内最高相似度（十进制字符串）")
    private String maxScore;

    @Schema(description = "成员；详情中的 row 为当前数据库中的记录，记录已不存在时 exists=false、row 为匹配时快照")
    private List<Member> members;

    @Schema(description = "组内达到阈值的记录对")
    private List<Pair> pairs;

    @Schema(description = "黄金记录建议（仅详情）")
    private Suggestion suggestion;

    private Long changeId;

    @Schema(description = "合并申请状态")
    private String changeStatus;

    private String handledByName;

    private LocalDateTime handledAt;

    private String handleRemark;

    private LocalDateTime createTime;

    @Data
    public static class Member {

        @Schema(description = "主键标识（与变更日志数据标识一致）")
        private String key;

        private Map<String, Object> row;

        private Boolean exists;
    }

    @Data
    public static class Pair {

        @Schema(description = "成员下标")
        private Integer left;

        private Integer right;

        private String score;

        @Schema(description = "逐字段得分")
        private Map<String, String> fields;
    }

    @Data
    public static class Suggestion {

        @Schema(description = "建议保留的成员下标")
        private Integer survivor;

        private Map<String, Object> values;

        @Schema(description = "各字段取值来源成员下标")
        private Map<String, Integer> sources;
    }
}
