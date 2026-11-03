package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import java.time.*;
import java.util.List;

import lombok.Data;

@Data
public class SafetyWhitelistWorkbenchRespVO {

    private Long id;

    private String sourceRecordId;

    private Long revision;

    private String name;

    private Long categoryId;

    private String categoryName;

    private String categorySourceRecordId;

    private String effectiveCategory;

    private List<Long> userIds;

    private List<User> users;

    private LocalDate startTime;

    private LocalDate endTime;

    private Short status;

    private String description;

    private String creatorName;

    private LocalDateTime createTime;

    private String updaterName;

    private LocalDateTime updateTime;

    private String authorizationBasis;

    @Data
    public static class User {

        private Long id;

        private String nickname;

        private String username;

        private String deptName;

        private String mobile;
    }
}
