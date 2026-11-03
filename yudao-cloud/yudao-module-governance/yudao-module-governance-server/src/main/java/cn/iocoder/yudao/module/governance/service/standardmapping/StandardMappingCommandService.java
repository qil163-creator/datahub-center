package cn.iocoder.yudao.module.governance.service.standardmapping;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardMappingMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_MAPPING_COMMAND_CONFLICT;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_MAPPING_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_MAPPING_STANDARD_NOT_PUBLISHED;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_MAPPING_STATUS_INVALID;

/**
 * 唯一落标命令：手工与内部 API 共用。查询接口不得调用本命令做隐式修复。
 */
@Service
public class StandardMappingCommandService {

    @Resource
    private StandardMappingMapper mappingMapper;

    @Resource
    private StandardElementMapper elementMapper;

    @Resource
    private MetaColumnMapper columnMapper;

    /**
     * 核验标准映射候选并确认落标关系。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long confirm(String targetType, String targetUrn, Integer elementId, Long columnId,
            String sourceType, String reason, String targetNameSnapshot) {

        MetaColumnDO column = lockMetadataColumn(targetType, targetUrn, columnId, true);
        StandardElementDO element = elementMapper.selectByIdForUpdate(elementId);
        if (element == null || !"PUBLISHED".equals(element.getLifecycleStatus())
                || element.getStatus() == null || element.getStatus() != 0) {
            throw exception(STANDARD_MAPPING_STANDARD_NOT_PUBLISHED);
        }
        String decidedBy = currentUser();
        LocalDateTime now = LocalDateTime.now();
        StandardMappingDO current = mappingMapper.selectConfirmedByTargetForUpdate(targetType, targetUrn);
        if (current != null) {
            if (Objects.equals(current.getElementId(), elementId)) {
                return current.getId();
            }
            int revoked = mappingMapper.updateById(StandardMappingDO.builder()
                    .id(current.getId())
                    .mappingStatus(StandardMappingDO.STATUS_REVOKED)
                    .confirmedBy(decidedBy)
                    .confirmedTime(now)
                    .deviationReason(StrUtil.blankToDefault(reason, "由新落标命令替换"))
                    .build());
            if (revoked != 1) {
                throw exception(STANDARD_MAPPING_COMMAND_CONFLICT);
            }
        }
        StandardMappingDO mapping = StandardMappingDO.builder()
                .elementId(elementId)
                .targetType(targetType)
                .columnId(columnId)
                .targetUrn(targetUrn)
                .targetNameSnapshot(targetNameSnapshot)
                .mappingStatus(StandardMappingDO.STATUS_CONFIRMED)
                .sourceType(StrUtil.blankToDefault(sourceType, StandardMappingDO.SOURCE_MANUAL))
                .confirmedBy(decidedBy)
                .confirmedTime(now)
                .deviationReason(reason)
                .build();
        mappingMapper.insert(mapping);
        if (column != null) {
            updateColumnStandard(column, elementId.longValue());
        }
        return mapping.getId();
    }

    /**
     * 撤回标准映射命令已生效的授权。
     */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long mappingId, String reason) {
        StandardMappingDO mapping = mappingMapper.selectById(mappingId);
        if (mapping == null) {
            throw exception(STANDARD_MAPPING_NOT_EXISTS);
        }
        MetaColumnDO column = lockMetadataColumn(mapping.getTargetType(), mapping.getTargetUrn(), mapping.getColumnId(),
                false);
        mapping = mappingMapper.selectByIdForUpdate(mappingId);
        if (mapping == null) {
            throw exception(STANDARD_MAPPING_NOT_EXISTS);
        }
        if (!StandardMappingDO.STATUS_CONFIRMED.equals(mapping.getMappingStatus())
                && !StandardMappingDO.STATUS_PROPOSED.equals(mapping.getMappingStatus())) {
            throw exception(STANDARD_MAPPING_STATUS_INVALID);
        }
        revokeLocked(mapping, column, reason);
    }

    /** 元数据删除只撤销当前映射的执行资格，历史映射行保持不变。 */
    @Transactional(rollbackFor = Exception.class)
    public void revokeForRetiredColumn(Long columnId, String reason) {
        MetaColumnDO column = columnMapper.selectByIdForUpdate(columnId);
        if (column == null || !Objects.equals(column.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(STANDARD_MAPPING_COMMAND_CONFLICT);
        }
        StandardMappingDO mapping = mappingMapper.selectConfirmedByTargetForUpdate(
                StandardMappingDO.TARGET_TYPE_METADATA_COLUMN, StandardMappingTargetSnapshot.targetUrn(column));
        if (mapping != null) {
            revokeLocked(mapping, column, reason);
        }
    }

    /**
     * 撤回已锁定。
     */
    private void revokeLocked(StandardMappingDO mapping, MetaColumnDO column, String reason) {
        int revoked = mappingMapper.updateById(StandardMappingDO.builder()
                .id(mapping.getId())
                .mappingStatus(StandardMappingDO.STATUS_REVOKED)
                .confirmedBy(currentUser())
                .confirmedTime(LocalDateTime.now())
                .deviationReason(reason)
                .build());
        if (revoked != 1) {
            throw exception(STANDARD_MAPPING_COMMAND_CONFLICT);
        }
        if (column != null && mapping.getElementId() != null
                && Objects.equals(column.getStandardElementId(), mapping.getElementId().longValue())) {
            updateColumnStandard(column, null);
        }
    }

    /** 统一按字段、标准、映射的顺序加锁，避免字段维护和独立落标命令相互倒置。 */
    private MetaColumnDO lockMetadataColumn(String targetType, String targetUrn, Long columnId, boolean requireActive) {
        if (!StandardMappingDO.TARGET_TYPE_METADATA_COLUMN.equals(targetType)) {
            return null;
        }
        if (columnId == null) {
            throw exception(STANDARD_MAPPING_COMMAND_CONFLICT);
        }
        MetaColumnDO column = columnMapper.selectByIdForUpdate(columnId);
        if (column == null || (requireActive && Objects.equals(column.getObjectStatus(),
                MetaDbDO.OBJECT_STATUS_DELETED))
                || !Objects.equals(StandardMappingTargetSnapshot.targetUrn(column), targetUrn)) {
            throw exception(STANDARD_MAPPING_COMMAND_CONFLICT);
        }
        return column;
    }

    /**
     * 更新字段标准。
     */
    private void updateColumnStandard(MetaColumnDO column, Long elementId) {
        int updated = columnMapper.update(null, Wrappers.lambdaUpdate(MetaColumnDO.class)
                .eq(MetaColumnDO::getId, column.getId())
                .eq(MetaColumnDO::getRevision, column.getRevision())
                .set(MetaColumnDO::getStandardElementId, elementId)
                .set(MetaColumnDO::getRevision, column.getRevision() + 1));
        if (updated != 1) {
            throw exception(STANDARD_MAPPING_COMMAND_CONFLICT);
        }
    }

    /**
     * 读取并校验当前标准映射操作人的用户编号。
     */
    private static String currentUser() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId == null ? "system" : String.valueOf(userId);
    }

}
