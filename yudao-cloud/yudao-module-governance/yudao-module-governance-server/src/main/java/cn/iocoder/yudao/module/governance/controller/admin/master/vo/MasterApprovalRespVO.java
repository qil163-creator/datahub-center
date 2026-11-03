package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterApprovalRespVO")
@Data
public class MasterApprovalRespVO {

    private Long id;

    private String dataId;

    private Long modelId;

    private String modelName;

    private String requestId;

    private String operation;

    private String status;

    private Long applicantId;

    private String applicantName;

    private LocalDateTime createTime;

    private Long reviewerId;

    private String reviewerName;

    private LocalDateTime reviewTime;

    private String reviewComment;

    private String processInstanceId;

    private String bpmProcessKey;

    private String lastError;

    private List<Map<String, Object>> rows;

    private List<Map<String, Object>> beforeRows;
}
