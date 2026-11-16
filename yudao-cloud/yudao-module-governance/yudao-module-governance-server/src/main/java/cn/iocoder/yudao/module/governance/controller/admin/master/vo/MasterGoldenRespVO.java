package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 黄金记录及其合并来源")
@Data
public class MasterGoldenRespVO {

    @Schema(description = "黄金记录主键标识")
    private String goldenKey;

    @Schema(description = "已合并进来的记录数")
    private Long mergedCount;

    private LocalDateTime lastMergedAt;

    @Schema(description = "合并来源（最近 50 条）")
    private List<Source> sources;

    @Data
    public static class Source {

        private String sourceKey;

        @Schema(description = "被合并记录合并前的完整内容")
        private Map<String, Object> sourceRow;

        private Long changeId;

        private Long groupId;

        private LocalDateTime mergedAt;

        private String mergedByName;
    }
}
