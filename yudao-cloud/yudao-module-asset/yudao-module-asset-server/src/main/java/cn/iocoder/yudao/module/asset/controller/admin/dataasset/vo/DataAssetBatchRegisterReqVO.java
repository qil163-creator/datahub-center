package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据资产批量编目 Request VO")
@Data
public class DataAssetBatchRegisterReqVO {

    @Valid
    @NotEmpty(message = "至少选择一项元数据")
    private List<DataAssetRegisterReqVO> items;

}
