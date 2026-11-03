package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 主数据域 Response VO")
@Data
public class MasterDomainRespVO {

    private Long id;

    private String code;

    private String name;

    private String description;

    private String remark;

    private Long leaderUserId;

    private String leaderUserName;

    private String leaderPhone;

    private String icon;

    private Integer sort;

    private Integer status;

    private Long modelCount;

    private String creator;

    private String creatorName;

    private LocalDateTime createTime;

    private String updater;

    private String updaterName;

    private LocalDateTime updateTime;
}
