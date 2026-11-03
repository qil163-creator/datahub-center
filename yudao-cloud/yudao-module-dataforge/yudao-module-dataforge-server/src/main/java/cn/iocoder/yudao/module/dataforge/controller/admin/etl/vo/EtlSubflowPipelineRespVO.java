package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 子流程候选摘要，不返回节点配置")
public class EtlSubflowPipelineRespVO {

    private Long id;

    private String name;
}
