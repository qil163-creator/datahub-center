package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetaFileTreeNodeVO {

    private String id;

    private String name;

    private String type;

    private String dbType;

    private Long sourceSystemId;

    private Long datasourceId;

    private String path;

    private List<MetaFileTreeNodeVO> children = new ArrayList<>();
}
