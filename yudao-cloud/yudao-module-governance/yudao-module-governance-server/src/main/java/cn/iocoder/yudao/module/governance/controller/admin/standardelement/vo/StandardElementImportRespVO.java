package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 标准数据元导入 Response VO")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardElementImportRespVO {

    @Schema(description = "创建成功的数据元英文名数组", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> createNames;

    @Schema(description = "更新成功的数据元英文名数组", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> updateNames;

    @Schema(description = "导入失败的数据元集合，key 为英文名，value 为失败原因", requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, String> failureReasons;

}
