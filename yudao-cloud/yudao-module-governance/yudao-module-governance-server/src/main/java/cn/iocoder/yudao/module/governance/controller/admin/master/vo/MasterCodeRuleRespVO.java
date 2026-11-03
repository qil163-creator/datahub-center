package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterCodeRuleRespVO")
@Data
public class MasterCodeRuleRespVO {

    private Long id;

    private Long modelId;

    private String modelName;

    private String code;

    private String name;

    private String fieldName;

    private String description;

    private Integer status;

    private List<MasterCodeSegmentVO> segments;

    private String example;

    private LocalDateTime createTime;
}
