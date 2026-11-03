package cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理后台 - 概念模型响应；分页不返回设计正文")
public class ConceptModelRespVO {

    private Long id;

    private String name;

    private String code;

    private Long dataDomainId;

    private String dataDomainName;

    private Long subjectDomainId;

    private String subjectDomainName;

    private String description;

    private String status;

    private Long revision;

    private ConceptModelDesignVO design;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
