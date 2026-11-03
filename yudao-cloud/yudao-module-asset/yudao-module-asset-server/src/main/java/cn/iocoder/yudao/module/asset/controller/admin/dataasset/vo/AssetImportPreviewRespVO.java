package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssetImportPreviewRespVO extends DatabasePreviewRespDTO {

    private Long total;

    private List<String> rowIds;

    private List<Integer> rowRevisions;
}
