package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 采集连接的库表范围")
public class MetaCollectScopeRespVO {

    private String db;

    private List<String> tables;
}
