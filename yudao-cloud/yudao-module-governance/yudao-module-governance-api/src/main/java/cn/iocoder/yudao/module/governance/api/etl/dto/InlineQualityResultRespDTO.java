package cn.iocoder.yudao.module.governance.api.etl.dto;

import lombok.Data;

@Data
public class InlineQualityResultRespDTO {

    /** ACCEPTED、REPLAY 或 CONFLICT（接受、重放或冲突）。 */
    private String status;

    private Long reportId;
}
