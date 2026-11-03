package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "管理后台 - 实时授权问题行的有界结果")
public class QualityIssueRowPageRespVO {

    private Long issueId;

    private List<String> columns;

    private List<Row> list;

    private Long total;

    private Boolean truncated;

    @Data
    public static class Row {

        private String rowKey;

        private Map<String, String> values;

        private String state;

        private Integer revision;

        private String remark;

        private Boolean processable;
    }
}
