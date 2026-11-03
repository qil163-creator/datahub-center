package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class PushCategoryRespVO {
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
