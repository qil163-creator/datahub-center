package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 资产列表左侧分组统计：按业务分类 / 主题域 / 数仓分层，各分组带资产数量
 */
@Schema(description = "管理后台 - 数据资产分组统计 Response VO")
@Data
public class DataAssetFacetRespVO {

    @Schema(description = "资产总数")
    private Long total;

    @Schema(description = "按资产类目统计（含全部类目节点，数量为直接挂接数）")
    private List<Facet> categories;

    @Schema(description = "按主题域统计")
    private List<Facet> subjectDomains;

    @Schema(description = "按数仓分层统计")
    private List<Facet> layers;

    @Schema(description = "分组项")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Facet {

        @Schema(description = "分组键：类目编号 / 主题域编号 / 分层编码")
        private String key;

        private String label;

        @Schema(description = "父级键，仅类目树有值")
        private String parentKey;

        private Long count;

    }

}
