package cn.iocoder.yudao.module.governance.api.metadata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量读取表当前有效字段名的请求，供血缘解析展开 {@code SELECT *}。
 */
@Schema(description = "RPC 服务 - 批量读取表字段名请求")
@Data
public class MetadataTableColumnsBatchReqDTO {

    /** 单次最多表数 */
    public static final int MAX_TABLES = 100;

    @Schema(description = "要读取的表，最多 100 个", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "表列表不能为空")
    @Size(max = MAX_TABLES, message = "单次最多读取 100 个表")
    @Valid
    private List<MetadataTableKeyDTO> tables = new ArrayList<>();

}
