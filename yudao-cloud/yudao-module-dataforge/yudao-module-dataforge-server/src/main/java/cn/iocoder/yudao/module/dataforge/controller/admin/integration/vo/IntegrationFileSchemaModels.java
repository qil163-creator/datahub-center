package cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

/** 文件字段解析合同；只接受上传编号，不接受URL或存储路径。 */
public final class IntegrationFileSchemaModels {
    private IntegrationFileSchemaModels() { }

    @Data
    public static class Request {
        @NotNull @Positive private Long fileId;
        @NotNull @Pattern(regexp = "CSV|EXCEL|JSONL") private String format;
        @NotNull @Min(1) @Max(10000) private Integer startRow = 2;
        @NotNull @Min(1) @Max(256) private Integer startColumn = 1;
        @Size(max = 128) private String sheetName;
    }

    public record SchemaField(String name, String dataType, Integer length, Integer precision, Integer scale, String path) { }
    public record Response(List<SchemaField> fields, List<String> warnings, boolean partial) { }
}
