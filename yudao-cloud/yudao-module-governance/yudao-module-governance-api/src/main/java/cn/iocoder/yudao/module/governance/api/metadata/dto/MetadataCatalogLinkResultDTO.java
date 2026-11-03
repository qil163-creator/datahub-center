package cn.iocoder.yudao.module.governance.api.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetadataCatalogLinkResultDTO {

    private String operationId;

    private String requestHash;

    private Long sourceRevision;

    /** APPLIED / REJECTED / NOT_FOUND；通讯失败由消费方保留 UNKNOWN。 */
    private String outcome;

    private String reason;
}
