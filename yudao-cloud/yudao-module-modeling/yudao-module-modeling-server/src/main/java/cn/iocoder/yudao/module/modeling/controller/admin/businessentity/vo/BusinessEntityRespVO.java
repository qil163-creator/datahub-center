package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class BusinessEntityRespVO {

    private Long id;

    private Long dataDomainId;

    private String dataDomainName;

    private Long subjectDomainId;

    private String subjectDomainName;

    private String subjectDomainCode;

    private String name;

    private String code;

    private String entityType;

    private String entitySubtype;

    private Long leaderUserId;

    private String leaderName;

    private String leaderPhone;

    private String description;

    private Integer status;

    private Long revision;

    private String creator;

    private String creatorName;

    private String creatorPhone;

    private String updater;

    private String updaterName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long inheritEntityId;

    private List<Long> associatedEntityIds;

    private List<Long> predecessorEntityIds;

    private List<Long> processEventIds;

    private Summary inheritedEntity;

    private List<Summary> associatedEntities;

    private List<Summary> predecessorEntities;

    private List<Summary> processEvents;

    private Long logicalModelCount;

    /** 树分页中匹配筛选条件的流程事件，按包含关系顺序排列；普通分页不填充。 */
    private List<BusinessEntityRespVO> children;

    @Data
    public static class Summary {

        private Long id;

        private String name;

        private String code;

        private Long subjectDomainId;

        private String entityType;

        private String entitySubtype;

        private Integer status;
    }
}
