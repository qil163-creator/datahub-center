package cn.iocoder.yudao.module.bpm.api.task.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 已结束流程的权威回执，供业务方核对流程身份和完成时间。 */
@Data
public class BpmProcessInstanceResultRespDTO {
    private String id;
    private String processDefinitionKey;
    private String businessKey;
    private Integer status;
    private String reason;
    private LocalDateTime endTime;
}
