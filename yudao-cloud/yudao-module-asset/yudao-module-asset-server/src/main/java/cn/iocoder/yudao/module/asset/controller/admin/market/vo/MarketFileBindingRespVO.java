package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "市场交付文件的不可变版本信息")
public record MarketFileBindingRespVO(Long catalogVersionId, String fileName, Long size, String sha256) {}
