package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo;

import lombok.Data;

@Data
public class StandardCodeMappingRespVO {

    private Long id;

    private Integer codeSetId;

    private String originalValue;

    private Integer codeItemId;

    private Long revision;

    private String codeName;

    private String codeValue;

    private java.time.LocalDateTime createTime;

    private java.time.LocalDateTime updateTime;

    private String creator;

    private String creatorName;
}
