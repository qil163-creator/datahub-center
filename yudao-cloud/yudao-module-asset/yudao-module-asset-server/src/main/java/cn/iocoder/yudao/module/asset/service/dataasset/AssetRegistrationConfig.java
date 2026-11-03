package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetRegistrationConfigVO;
import cn.iocoder.yudao.module.modeling.api.registration.dto.RegistrationContextRespDTO;
import jakarta.annotation.Resource;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_CATALOG_NOT_READY;

/** 业务注册配置的白名单和校验边界，不承担物理元数据变更。 */
@Component
public class AssetRegistrationConfig {

    @Resource
    private Validator validator;

    @Resource
    private AssetRegistrationContextService contexts;

    public record Resolved(String json, String subjectDomainName) {

    }

    /**
     * 将资产登记配置序列化为持久化内容。
     */
    public String serialize(AssetRegistrationConfigVO config) {
        return resolve(config, null).json();
    }

    /**
     * 校验登记配置与业务板块、主题域及实体的关系，并补充来源名称。
     */
    public Resolved resolve(AssetRegistrationConfigVO input, Long subjectDomainId) {
        if (input == null) {
            return new Resolved(null, null);
        }
        ValidationUtils.validate(validator, input);
        var config = BeanUtils.toBean(input, AssetRegistrationConfigVO.class);
        RegistrationContextRespDTO context = null;
        String subjectName = null;
        if (config.getDataDomainId() != null) {
            context = contexts.context(config.getDataDomainId(), config.getTableType(), subjectDomainId);
            if (context == null || !Objects.equals(context.getSelectedDataDomainId(), config.getDataDomainId())) {
                invalid("业务板块不存在或当前不可用");
            }
            Long domainId = config.getDataDomainId();
            config.setDataDomainName(
                    safe(context.getDataDomains()).stream().filter(domain -> Objects.equals(domain.id(), domainId))
                            .map(RegistrationContextRespDTO.Domain::name).findFirst()
                            .orElseThrow(() -> exception(DATA_ASSET_CATALOG_NOT_READY, "业务板块不存在")));
            if (subjectDomainId != null) {
                subjectName = safe(context.getSubjectDomains()).stream()
                        .filter(subject -> Objects.equals(subject.id(), subjectDomainId) && Objects.equals(
                                subject.dataDomainId(), domainId))
                        .map(RegistrationContextRespDTO.Subject::name).findFirst()
                        .orElseThrow(() -> exception(DATA_ASSET_CATALOG_NOT_READY, "主题域不属于当前业务板块"));
            }
            if (config.getEntityId() != null) {
                var entity = safe(context.getEntities()).stream()
                        .filter(row -> Objects.equals(row.id(), config.getEntityId()))
                        .findFirst()
                        .orElseThrow(() -> exception(DATA_ASSET_CATALOG_NOT_READY, "业务实体不属于当前配置"));
                validateEntitySubject(entity, subjectDomainId, context);
                config.setBusinessEntity(entity.name());
            } else {
                config.setBusinessEntity(null);
            }
            if (config.getGranularityEntityIds() != null && !config.getGranularityEntityIds().isEmpty()) {
                List<String> labels = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                for (Long id : config.getGranularityEntityIds()) {
                    if (!seen.add(id)) {
                        invalid("统计粒度不能重复");
                    }
                    var entity = safe(context.getGranularities()).stream().filter(row -> Objects.equals(row.id(), id))
                            .findFirst()
                            .orElseThrow(() -> exception(DATA_ASSET_CATALOG_NOT_READY, "统计粒度不属于当前配置"));
                    validateEntitySubject(entity, subjectDomainId, context);
                    labels.add(entity.name());
                }
                config.setStatGranularity(String.join(",", labels));
            } else {
                config.setStatGranularity(null);
            }
        } else {
            config.setDataDomainName(null);
            if (config.getEntityId() != null || (config.getGranularityEntityIds() != null && !config.getGranularityEntityIds()
                    .isEmpty())) {
                invalid("选择业务实体或粒度前必须选择真实业务板块");
            }
        }
        if (StrUtil.isNotBlank(config.getLayer())) {
            if (context == null) {
                context = contexts.context(null, config.getTableType(), null);
            }
            String layer = config.getLayer();
            if (context == null || safe(context.getLayers()).stream()
                    .noneMatch(row -> Objects.equals(row.code(), layer))) {
                invalid("数仓分层不存在或当前不可用");
            }
        }
        ValidationUtils.validate(validator, config);
        return new Resolved(JsonUtils.toJsonString(config), subjectName);
    }

    /**
     * 校验资产登记的实体主体，不满足约束时拒绝当前操作。
     */
    private void validateEntitySubject(RegistrationContextRespDTO.Entity entity, Long subjectId,
            RegistrationContextRespDTO context) {
        if (subjectId != null && !Objects.equals(entity.subjectDomainId(), subjectId)) {
            invalid("实体或粒度不属于当前主题域");
        }
        if (entity.subjectDomainId() != null && safe(context.getSubjectDomains()).stream()
                .noneMatch(subject -> Objects.equals(subject.id(), entity.subjectDomainId()) && Objects.equals(
                        subject.dataDomainId(), context.getSelectedDataDomainId()))) {
            invalid("实体或粒度跨业务板块");
        }
    }

    /**
     * 将缺失的配置列表转换为空列表。
     */
    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    /**
     * 构造资产登记输入校验失败时的业务异常。
     */
    private static void invalid(String message) {
        throw exception(DATA_ASSET_CATALOG_NOT_READY, message);
    }
}
