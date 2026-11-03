package cn.iocoder.yudao.module.governance.controller.admin.master.vo;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterDataMutationRespVO")
@Data
public class MasterDataMutationRespVO {

    private Long id;

    private String status;

    private Boolean approvalRequired;

    private String message;
}
