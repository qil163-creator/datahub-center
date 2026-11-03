package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "管理后台 - 定版只读详情；技术结构为选定版本，业务信息为确认时镜像")
public class MetaFinalizedDetailRespVO {

    private MetaTableRespVO table;

    private List<MetaColumnRespVO> columns;

    private MetaSnapshotRespVO snapshot;
}
