package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 建表语句解析字段 Response VO")
@Data
public class SqlParseFieldsRespVO {

    @Schema(description = "建表语句中的表名", example = "t_order")
    private String tableName;

    @Schema(description = "建表语句中的表注释", example = "订单")
    private String tableComment;

    @Schema(description = "解析出的字段，按建表语句顺序；不含字段稳定标识，加入设计时由页面生成")
    private List<ModelFieldItem> fields;
}
