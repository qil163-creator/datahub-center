package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import lombok.Data;

@Data
public class EtlQualityBatchRespVO {

    private Long id;

    private Long recordId;

    private String nodeNo;

    private String planHash;

    private Long inputCount;

    private Long passCount;

    private Long warnCount;

    private Long rejectCount;

    private Long violationCount;

    private String registrationStatus;

    private Long governanceReportId;
}
