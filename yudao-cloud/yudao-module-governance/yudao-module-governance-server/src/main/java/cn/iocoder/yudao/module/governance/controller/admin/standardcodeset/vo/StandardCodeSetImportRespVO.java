package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 标准码表/码值导入 Response VO")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardCodeSetImportRespVO {

    @Schema(description = "新建的码表字典类型数组", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> createCodeSetTypes;

    @Schema(description = "新建的码值（字典类型=键值）数组", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> createItemKeys;

    @Schema(description = "更新的码值（字典类型=键值）数组", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> updateItemKeys;

    @Schema(description = "导入失败的行集合，key 为行标识，value 为失败原因", requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, String> failureReasons;

}
