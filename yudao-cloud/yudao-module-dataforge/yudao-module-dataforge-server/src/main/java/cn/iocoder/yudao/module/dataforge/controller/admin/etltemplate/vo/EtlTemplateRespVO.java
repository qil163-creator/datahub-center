package cn.iocoder.yudao.module.dataforge.controller.admin.etltemplate.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class EtlTemplateRespVO {

    private Long id;

    private String name;

    private String description;

    private Long ownerUserId;

    private Integer nodeCount;

    private boolean canDelete;

    private LocalDateTime createTime;

    /** 仅详情返回，读取时重新执行数据源与治理引用授权。 */
    private List<Map<String, Object>> nodes;

    private List<Map<String, Object>> edges;
}
