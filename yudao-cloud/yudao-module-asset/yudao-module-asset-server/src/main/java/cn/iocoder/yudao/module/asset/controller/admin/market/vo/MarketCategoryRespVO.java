package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 集市类目")
@Data
public class MarketCategoryRespVO {

    private Long id;

    private Long parentId;

    private String name;

    private String description;

    private Short status;

    private Integer sort;

    private String creator;

    private String creatorName;

    private LocalDateTime createTime;
}
