package cn.iocoder.yudao.module.modeling.api.registration.dto;

import lombok.Data;

import java.util.List;

/** 仅暴露业务选项白名单，不包含负责人、电话或可执行连接。 */
@Data
public class RegistrationContextRespDTO {

    private List<Domain> dataDomains;

    private Long selectedDataDomainId;

    private String selectionSource;

    private List<Subject> subjectDomains;

    private List<Layer> layers;

    private List<Entity> entities;

    private List<Entity> granularities;

    private List<NamingRule> namingRules;

    private List<TimeEffect> timeEffects;

    public record Domain(Long id, String name, String code) {

    }

    public record Subject(Long id, Long parentId, Long dataDomainId, String name, String code, String abbreviation) {

    }

    public record Layer(Long id, String name, String code, String tablePrefix, String defaultTableType) {

    }

    public record Entity(Long id, String name, String code, Long subjectDomainId, String entityType,
                         String entitySubtype) {

    }

    public record NamingRule(String tableType, String prefix, String daySuffix, String hourSuffix, String minuteSuffix,
                             Boolean includeSubjectAbbreviation) {

    }

    public record TimeEffect(String value, String label) {

    }
}
