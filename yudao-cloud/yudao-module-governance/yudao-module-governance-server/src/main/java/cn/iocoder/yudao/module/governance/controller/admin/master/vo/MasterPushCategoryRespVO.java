package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MasterPushCategoryRespVO {
    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;
    private Integer status;
    private String description;
    private String creator;
    private String creatorName;
    private LocalDateTime createTime;
    private Long childCount;
    private Long taskCount;
}
