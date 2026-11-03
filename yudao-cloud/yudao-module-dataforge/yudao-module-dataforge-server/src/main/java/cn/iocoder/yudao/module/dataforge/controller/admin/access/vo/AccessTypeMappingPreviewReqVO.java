package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 表源跨产品类型映射预览；不执行 DDL 或搬数")
@Data
@EqualsAndHashCode(callSuper = true)
public class AccessTypeMappingPreviewReqVO extends DataAccessPreviewReqVO {

    /**
     * 创建访问类型映射预览实例。
     */
    public AccessTypeMappingPreviewReqVO() {
        setSourceType(1);
    }

    /**
     * 读取目标数据源编号。
     *
     * @return 目标数据源编号
     */
    @NotNull(message = "目的端数据源不能为空")
    @Schema(description = "目的端数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @Override
    public Long getTargetDatabaseId() {
        return super.getTargetDatabaseId();
    }
}
