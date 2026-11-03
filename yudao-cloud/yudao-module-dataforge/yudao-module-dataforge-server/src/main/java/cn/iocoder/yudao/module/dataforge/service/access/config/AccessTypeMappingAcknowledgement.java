package cn.iocoder.yudao.module.dataforge.service.access.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 任务拥有的逐目标列决策。仅保存预览身份和用户选择，不保存连接、驱动或授权令牌。 */
public record AccessTypeMappingAcknowledgement(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String targetProduct,
        @NotBlank @Size(max = 128) String targetSchema,
        @NotBlank @Size(max = 256) String targetTable,
        @NotBlank @Size(max = 256) String column,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String mappingFingerprint,
        @NotEmpty @Size(max = 100) List<@Pattern(regexp = "[A-Z][A-Z0-9_]{0,95}") String> lossCodes) {

    /**
     * 创建访问类型映射确认回执实例。
     *
     * @param targetProduct 目标产品
     * @param targetSchema 目标结构
     * @param targetTable 目标表
     * @param column 列
     * @param mappingFingerprint 映射指纹
     * @param lossCodes 损失编码
     */
    public AccessTypeMappingAcknowledgement {
        if (targetProduct == null || !targetProduct.matches("[A-Z][A-Z0-9_]{0,31}")
                || !name(targetSchema, 128) || !name(targetTable, 256) || !name(column, 256)
                || mappingFingerprint == null || !mappingFingerprint.matches("[0-9a-f]{64}")
                || lossCodes == null || lossCodes.isEmpty() || lossCodes.size() > 100
                || lossCodes.stream().anyMatch(code -> code == null || !code.matches("[A-Z][A-Z0-9_]{0,95}"))
                || lossCodes.stream().distinct().count() != lossCodes.size()) {
            throw new IllegalArgumentException("有损类型映射决策格式无效");
        }
        lossCodes = List.copyOf(lossCodes);
    }

    /**
     * 读取访问类型映射确认回执名称。
     *
     * @param value 待处理的值
     * @param max 最大值
     * @return 处理是否成功
     */
    private static boolean name(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.indexOf('\0') < 0;
    }
}
