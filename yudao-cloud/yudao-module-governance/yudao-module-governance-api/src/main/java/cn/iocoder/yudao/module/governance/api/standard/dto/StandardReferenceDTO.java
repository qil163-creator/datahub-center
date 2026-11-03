package cn.iocoder.yudao.module.governance.api.standard.dto;

import lombok.Data;

@Data
public class StandardReferenceDTO {

    private String kind;

    private Long id;

    /** 标准为发布版本号，来源为发布快照编号，术语为定义内容摘要（术语暂无发布版本表）。 */
    private String version;

    private String name;

    private String definition;

    private String evidenceHash;

    private boolean active;

    private String reason;
}
