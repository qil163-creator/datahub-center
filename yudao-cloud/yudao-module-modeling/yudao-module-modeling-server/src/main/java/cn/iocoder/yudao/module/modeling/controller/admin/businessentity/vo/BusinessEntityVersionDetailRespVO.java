package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Map;

@Schema(description = "管理后台 - 业务实体版本快照 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class BusinessEntityVersionDetailRespVO extends BusinessEntityVersionRespVO {

    @Schema(description = "上线时的实体信息与关系快照；relations 为 relationType、entityId、name、code 列表")
    private Map<String, Object> snapshot;
}
