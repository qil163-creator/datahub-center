package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EtlQualityIssueRespVO {

    private Long id;

    private Long batchId;

    private Long rowNumber;

    private String qualityStatus;

    private String violationsJson;

    private String rowJson;

    /** 真实原来源已按当前主体重新验证；不表示目标表准入。 */
    private boolean sourceVerified;

    private LocalDateTime createTime;
}
