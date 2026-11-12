package cn.iocoder.yudao.module.supply.api.dataapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * RPC - 发布了指定指标的数据服务接口（供指标口径变更影响分析）。不含认证信息与请求参数。
 */
@Schema(description = "RPC 服务 - 引用指标的数据服务接口")
@Data
public class SupplyIndicatorApiRefRespDTO {

    @Schema(description = "接口编号", example = "1")
    private Long id;

    @Schema(description = "接口名称")
    private String name;

    @Schema(description = "请求路径")
    private String path;

    @Schema(description = "状态：0 下线、1 上线")
    private Integer status;

    @Schema(description = "接口发布的、属于查询范围内的指标编号")
    private List<Long> indicatorIds = new ArrayList<>();
}
