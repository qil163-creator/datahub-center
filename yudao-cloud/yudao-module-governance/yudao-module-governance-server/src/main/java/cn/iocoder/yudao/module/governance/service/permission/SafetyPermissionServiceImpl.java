package cn.iocoder.yudao.module.governance.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.*;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.permission.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.*;
import cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.governance.service.permission.SafetyPermissionBindingResolver.*;

@Service
@Validated
public class SafetyPermissionServiceImpl implements SafetyPermissionService {

    /** 权限管理页按数据库、schema 和物理表分组配置。 */
    private record Key(Long database, String schema, String table) {

    }

    @Resource
    private SafetyTablePermissionMapper tablePermissionMapper;

    @Resource
    private SafetyFieldPermissionMapper fieldPermissionMapper;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private MetaDbMapper metaDbMapper;

    @Resource
    private MetaTableMapper metaTableMapper;

    @Resource
    private MetaColumnMapper metaColumnMapper;

    @Resource
    private SafetyRuleService ruleService;

    @Resource
    private SafetyPermissionBindingResolver bindingResolver;

    @Resource
    private SafetyWritePolicyAuditMapper writePolicyAuditMapper;

    /**
     * 保存表与字段权限配置。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(SafetyPermissionSaveReqVO request) {
        Long actor = requireActor();
        if (request == null || blank(request.getSchemaName()) || blank(request.getTableName())) {
            throw exception(PERMISSION_TABLE_REQUIRED);
        }
        var verified = bindingResolver.verifiedTable(request.getDatabaseId(), request.getTableReference(),
                request.getSchemaName(), request.getTableName());
        var binding = verified.table();
        Set<String> physical = new HashSet<>(
                verified.columns().stream().map(DatabaseColumnRespDTO::getColumnName).toList());
        if (CollUtil.isEmpty(request.getRolePermissionConfigs())) {
            throw exception(PERMISSION_ROLE_REQUIRED);
        }
        Set<Long> roles = new HashSet<>();
        for (var role : request.getRolePermissionConfigs()) {
            if (role == null || role.getRoleId() == null || role.getRoleId() <= 0 || !roles.add(role.getRoleId())) {
                throw exception(PERMISSION_ROLE_REQUIRED);
            }
            Set<String> fields = new HashSet<>();
            if (role.getTablePermission() != null && role.getTablePermission().getWritePolicy() != null) {
                if (Objects.equals(role.getTablePermission().getPermissionType(), SafetyTablePermissionDO.TYPE_DENY)) {
                    throw rejected("禁止访问的表不能配置写授权");
                }
                SafetyWritePermissionService.grant(role.getTablePermission().getWritePolicy(), physical);
            }
            if (role.getFieldPermissions() != null) {
                for (var field : role.getFieldPermissions()) {
                    if (field == null || !physical.contains(field.getFieldName()) || !fields.add(
                            field.getFieldName())) {
                        throw rejected("字段配置不是唯一的精确物理列");
                    }
                }
            }
        }
        validatePermissionTypes(request);
        validateMaskingRules(request);
        bindingResolver.assertCurrent(request.getDatabaseId(), verified.route());
        LocalDateTime now = LocalDateTime.now();
        for (var role : request.getRolePermissionConfigs()) {
            if (role.getTablePermission() != null) {
                saveTable(request.getDatabaseId(), binding.getSchemaName(), binding.getTableName(), role, actor, now);
            }
            saveFields(request.getDatabaseId(), binding.getSchemaName(), binding.getTableName(), role, actor, now);
        }
    }

    /**
     * 读取数据安全权限的配置。
     */
    @Override
    public SafetyPermissionSaveReqVO getConfig(Long databaseId, String tableName, Long roleId) {
        throw rejected("旧配置签名缺少 schema 和精确引用，请升级调用方");
    }

    /**
     * 获得指定表、角色的权限配置。
     */
    @Override
    public SafetyPermissionSaveReqVO getConfig(Long databaseId, String schemaName, String tableName,
            String tableReference, Long roleId) {

        requireRole(roleId);
        var binding = bindingResolver.table(databaseId, tableReference,
                schemaName, tableName);
        var table = tablePermissionMapper.selectByTableRole(databaseId, binding.getSchemaName(), binding.getTableName(),
                roleId);
        var fields = fieldPermissionMapper.selectListByTableRole(databaseId, binding.getSchemaName(),
                binding.getTableName(), roleId);
        var result = config(new Key(databaseId, binding.getSchemaName(), binding.getTableName()), roleId, table,
                fields);
        result.setTableReference(binding.getTableReference());
        return result;
    }

    /**
     * 获得角色已配置的全部表权限。
     */
    @Override
    public List<SafetyPermissionSaveReqVO> getRolePermissions(Long roleId) {
        requireRole(roleId);
        Map<Key, SafetyTablePermissionDO> tables = new LinkedHashMap<>();
        for (var table : tablePermissionMapper.selectListByRoleId(roleId)) {
            Key key = new Key(table.getDatabaseId(), table.getSchemaName(), table.getTableName());
            if (tables.putIfAbsent(key, table) != null) {
                throw rejected("同一精确对象/历史组有重复表策略，请先核验处理，不能任取一条");
            }
        }
        Map<Key, List<SafetyFieldPermissionDO>> fields = new LinkedHashMap<>();
        for (var field : fieldPermissionMapper.selectListByRoleId(roleId)) {
            fields.computeIfAbsent(new Key(field.getDatabaseId(), field.getSchemaName(), field.getTableName()),
                    ignored -> new ArrayList<>()).add(field);
        }
        Set<Key> keys = new LinkedHashSet<>(tables.keySet());
        keys.addAll(fields.keySet());
        List<SafetyPermissionSaveReqVO> result = new ArrayList<>();
        for (Key key : keys) {
            var item = config(key, roleId, tables.get(key), fields.getOrDefault(key, List.of()));
            // 历史策略只展示待核验身份，不自动绑定；不受支持产品也保留可见。
            if (key.schema() != null) {
                item.setTableReference(
                        bindingResolver.binding(key.database(), key.schema(), key.table()).getTableReference());
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 获得可配置权限的数据源列表。
     */
    @Override
    public List<SafetyPermissionDatabaseRespVO> getDatabaseList() {
        List<DatabaseConnectionRespDTO> list = databaseApi.getDatabaseList(null).getCheckedData();
        if (list == null) {
            throw rejected("数据源清单不可用");
        }
        return list.stream().map(item -> {
            SafetyPermissionDatabaseRespVO vo = new SafetyPermissionDatabaseRespVO();
            vo.setId(item.getId());
            vo.setName(item.getName());
            vo.setUsageType(item.getUsageType());
            vo.setProductType(item.getProductType());
            return vo;
        }).toList();
    }

    /**
     * 获得数据源下的表清单。
     */
    @Override
    public List<SafetyPermissionTableRespVO> getTableList(Long databaseId) {
        var route = bindingResolver.route(databaseId);
        List<DatabaseQueryTableRespDTO> tables = databaseApi.getQueryTableList(databaseId).getCheckedData();
        if (tables == null || tables.size() > 2000) {
            throw rejected("精确表清单不可用或超过上限，不回退历史元数据");
        }
        Set<Key> seen = new HashSet<>();
        List<SafetyPermissionTableRespVO> result = new ArrayList<>();
        for (var table : tables) {
            requireTable(table);
            if (!seen.add(new Key(databaseId, table.getSchemaName(), table.getTableName()))) {
                throw rejected("精确表清单存在重复身份");
            }
            SafetyPermissionTableRespVO vo = new SafetyPermissionTableRespVO();
            vo.setSchemaName(table.getSchemaName());
            vo.setTableName(table.getTableName());
            vo.setTableReference(table.getTableReference());
            MetaTableDO meta = exactMetadata(databaseId, table.getSchemaName(), table.getTableName());
            vo.setViewTable(meta != null && "VIEW".equalsIgnoreCase(meta.getTableType()));
            if (meta != null) {
                vo.setRemarks(StrUtil.blankToDefault(meta.getCustomComment(), meta.getSourceComment()));
            }
            result.add(vo);
        }
        bindingResolver.assertCurrent(databaseId, route);
        return result;
    }

    /**
     * 读取数据安全权限字段列表。
     */
    @Override
    public List<SafetyPermissionColumnRespVO> getColumnList(Long databaseId, String tableName) {
        throw rejected("旧字段签名缺少 schema 和精确引用，请升级调用方");
    }

    /**
     * 获得表字段列表（附带元数据注释与分级）。
     */
    @Override
    public List<SafetyPermissionColumnRespVO> getColumnList(Long databaseId, String schemaName,
            String tableName, String tableReference) {

        var verified = bindingResolver.verifiedTable(databaseId, tableReference,
                schemaName, tableName);
        var binding = verified.table();
        var columns = verified.columns();
        MetaTableDO meta = exactMetadata(databaseId, binding.getSchemaName(), binding.getTableName());
        Map<String, MetaColumnDO> metadata = new HashMap<>();
        if (meta != null) {
            for (var column : metaColumnMapper.selectListByTableId(meta.getId())) {
                if (!blank(column.getColumnName()) && metadata.putIfAbsent(column.getColumnName(), column) != null) {
                    throw rejected("精确元数据存在重复字段，不能任取一条");
                }
            }
        }
        return columns.stream().map(column -> {
            SafetyPermissionColumnRespVO vo = new SafetyPermissionColumnRespVO();
            vo.setFieldName(column.getColumnName());
            vo.setFieldType(column.getTypeName());
            MetaColumnDO item = metadata.get(column.getColumnName());
            vo.setPkFlag(item != null && Objects.equals(item.getPkFlag(), 1));
            if (item != null) {
                vo.setFieldComment(StrUtil.blankToDefault(item.getCustomComment(),
                        StrUtil.blankToDefault(item.getBusinessName(), item.getSourceComment())));
                vo.setSafetyLevelId(item.getSafetyLevelId());
            }
            return vo;
        }).toList();
    }

    /**
     * 按数据源、模式和物理表精确查找元数据，拒绝重复匹配。
     */
    private MetaTableDO exactMetadata(Long databaseId, String schemaName, String tableName) {
        // 注释只按精确 schema + datasource + 物理表富化；探源失败不能用缓存构成授权身份。
        List<MetaDbDO> databases = metaDbMapper.selectListByDatasourceId(databaseId);
        List<Long> dbIds = databases.stream().filter(db -> Objects.equals(db.getSchemaName(), schemaName))
                .map(MetaDbDO::getId).toList();
        List<MetaTableDO> matches = metaTableMapper.selectListByDatasourceIdAndName(databaseId, tableName).stream()
                .filter(table -> dbIds.contains(table.getDbId())).toList();
        if (matches.size() > 1) {
            throw rejected("元数据的精确 schema/table 不唯一，请先核验");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * 核验后将历史策略绑定到精确物理模式和表，不覆盖或自动合并。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindLegacy(SafetyPermissionLegacyBindReqVO request) {
        Long actor = requireActor();
        requireRole(request.getRoleId());
        if (blank(request.getNote()) || request.getNote().length() > 500 || blank(request.getLegacyTableName())) {
            throw rejected("历史绑定必须填写核验说明");
        }
        // 所有跨服务核验都在获取行锁之前完成。
        var verified = bindingResolver.verifiedTable(request.getDatabaseId(), request.getTableReference(),
                request.getSchemaName(), request.getTableName());
        var target = verified.table();
        Set<String> physical = new HashSet<>(
                verified.columns().stream().map(DatabaseColumnRespDTO::getColumnName).toList());
        if (!Objects.equals(request.getLegacyTableName(), target.getTableName())) {
            bindingResolver.table(request.getDatabaseId(), request.getLegacyTableName(),
                    target.getSchemaName(), target.getTableName(), verified.route());
        }
        bindingResolver.assertCurrent(request.getDatabaseId(), verified.route());
        var tables = tablePermissionMapper.selectLegacyForUpdate(request.getDatabaseId(),
                request.getLegacyTableName(), request.getRoleId());
        var fields = fieldPermissionMapper.selectLegacyForUpdate(request.getDatabaseId(),
                request.getLegacyTableName(), request.getRoleId());
        if (tables.size() > 1 || (tables.isEmpty() && fields.isEmpty())) {
            throw rejected("历史组不存在或有重复表策略，不能自动合并");
        }
        Set<String> seen = new HashSet<>();
        for (var field : fields) {
            if (!physical.contains(field.getFieldName()) || !seen.add(field.getFieldName())) {
                throw rejected("历史字段有重复或不匹配精确物理列，需要先核验修正");
            }
        }
        if (tablePermissionMapper.selectByTableRole(request.getDatabaseId(), target.getSchemaName(),
                target.getTableName(), request.getRoleId()) != null
                || !fieldPermissionMapper.selectListByTableRole(request.getDatabaseId(), target.getSchemaName(),
                target.getTableName(), request.getRoleId()).isEmpty()) {
            throw rejected("目标对象已有策略，不能覆盖或自动合并历史策略");
        }
        LocalDateTime now = LocalDateTime.now();
        for (var table : tables) {
            checkChanged(tablePermissionMapper.update(null, new LambdaUpdateWrapper<SafetyTablePermissionDO>()
                    .eq(SafetyTablePermissionDO::getId, table.getId())
                    .eq(SafetyTablePermissionDO::getDatabaseId, request.getDatabaseId())
                    .eq(SafetyTablePermissionDO::getRoleId, request.getRoleId())
                    .isNull(SafetyTablePermissionDO::getSchemaName)
                    .set(SafetyTablePermissionDO::getSchemaName, target.getSchemaName())
                    .set(SafetyTablePermissionDO::getTableName, target.getTableName())
                    .set(SafetyTablePermissionDO::getBindingNote, request.getNote().trim())
                    .set(SafetyTablePermissionDO::getBindingActorId, actor)
                    .set(SafetyTablePermissionDO::getBindingTime, now)));
        }
        for (var field : fields) {
            checkChanged(fieldPermissionMapper.update(null, new LambdaUpdateWrapper<SafetyFieldPermissionDO>()
                    .eq(SafetyFieldPermissionDO::getId, field.getId())
                    .eq(SafetyFieldPermissionDO::getDatabaseId, request.getDatabaseId())
                    .eq(SafetyFieldPermissionDO::getRoleId, request.getRoleId())
                    .isNull(SafetyFieldPermissionDO::getSchemaName)
                    .set(SafetyFieldPermissionDO::getSchemaName, target.getSchemaName())
                    .set(SafetyFieldPermissionDO::getTableName, target.getTableName())
                    .set(SafetyFieldPermissionDO::getBindingNote, request.getNote().trim())
                    .set(SafetyFieldPermissionDO::getBindingActorId, actor)
                    .set(SafetyFieldPermissionDO::getBindingTime, now)));
        }
    }

    /**
     * 保存数据安全权限的表。
     */
    private void saveTable(Long db, String schema, String table, SafetyPermissionSaveReqVO.RolePermissionConfig role,
            Long actor, LocalDateTime now) {
        var entity = tablePermissionMapper.selectByTableRole(db, schema, table, role.getRoleId());
        boolean insert = entity == null;
        String beforePolicy = entity == null ? null : entity.getWritePolicy();
        String afterPolicy = role.getTablePermission().getWritePolicy() == null ? null
                : cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(
                role.getTablePermission().getWritePolicy());
        boolean writeChanged = !Objects.equals(beforePolicy, afterPolicy);
        String reason = role.getTablePermission().getWriteChangeReason();
        if (writeChanged && (blank(reason) || reason.trim().length() < 5 || reason.length() > 500)) {
            throw rejected("写授权变更必须填写 5 到 500 字的依据，撤销也必须留痕");
        }
        if (insert) {
            entity = new SafetyTablePermissionDO();
            entity.setDatabaseId(db);
            entity.setSchemaName(schema);
            entity.setTableName(table);
            entity.setRoleId(role.getRoleId());
        }
        entity.setPermissionType(role.getTablePermission().getPermissionType());
        entity.setWritePolicy(afterPolicy);
        entity.setSecurityLevelId(role.getTablePermission().getSecurityLevelId());
        entity.setStatus(0);
        entity.setBindingActorId(actor);
        entity.setBindingTime(now);
        entity.setBindingNote("权限页精确对象配置");
        if (insert) {
            checkChanged(tablePermissionMapper.insert(entity));
        } else {
            var condition = new LambdaUpdateWrapper<SafetyTablePermissionDO>()
                    .eq(SafetyTablePermissionDO::getId, entity.getId());
            if (beforePolicy == null) {
                condition.isNull(SafetyTablePermissionDO::getWritePolicy);
            } else {
                condition.eq(SafetyTablePermissionDO::getWritePolicy, beforePolicy);
            }
            checkChanged(tablePermissionMapper.update(entity, condition));
        }
        if (writeChanged) {
            checkChanged(writePolicyAuditMapper.record(
                    db, schema, table, role.getRoleId(), actor, beforePolicy, afterPolicy, reason.trim()));
        }
    }

    /**
     * 保存数据安全权限的字段。
     */
    private void saveFields(Long db, String schema, String table, SafetyPermissionSaveReqVO.RolePermissionConfig role,
            Long actor, LocalDateTime now) {
        var existing = fieldPermissionMapper.selectListByTableRole(db, schema, table, role.getRoleId());
        Map<String, SafetyFieldPermissionDO> byName = new HashMap<>();
        for (var item : existing) {
            if (byName.putIfAbsent(item.getFieldName(), item) != null) {
                throw rejected("精确字段策略重复，不能任取一条");
            }
        }
        Set<String> incoming = new HashSet<>();
        for (var field : role.getFieldPermissions() == null
                ? Collections.<SafetyPermissionSaveReqVO.FieldPermission>emptyList() : role.getFieldPermissions()) {
            if (!shouldPersistField(field)) {
                continue;
            }
            incoming.add(field.getFieldName());
            var entity = byName.get(field.getFieldName());
            boolean insert = entity == null;
            if (insert) {
                entity = new SafetyFieldPermissionDO();
                entity.setDatabaseId(db);
                entity.setSchemaName(schema);
                entity.setTableName(table);
                entity.setRoleId(role.getRoleId());
                entity.setFieldName(field.getFieldName());
            }
            entity.setPermissionType(field.getPermissionType());
            entity.setMaskingRuleId(field.getMaskingRuleId());
            entity.setSecurityLevelId(field.getSecurityLevelId());
            entity.setStatus(0);
            entity.setBindingActorId(actor);
            entity.setBindingTime(now);
            entity.setBindingNote("权限页精确对象配置");
            checkChanged(insert ? fieldPermissionMapper.insert(entity) : fieldPermissionMapper.updateById(entity));
        }
        for (var item : existing) {
            if (!incoming.contains(item.getFieldName())) {
                checkChanged(fieldPermissionMapper.deleteById(item.getId()));
            }
        }
    }

    /**
     * 组装数据安全权限配置。
     */
    private SafetyPermissionSaveReqVO config(Key key, Long roleId, SafetyTablePermissionDO table,
            List<SafetyFieldPermissionDO> fields) {
        SafetyPermissionSaveReqVO result = new SafetyPermissionSaveReqVO();
        result.setDatabaseId(key.database());
        result.setSchemaName(key.schema());
        result.setTableName(key.table());
        result.setLegacyUnbound(key.schema() == null);
        result.setRolePermissionConfigs(List.of(buildRoleConfig(roleId, table, fields)));
        return result;
    }

    /**
     * 构造数据安全权限的角色配置。
     */
    private SafetyPermissionSaveReqVO.RolePermissionConfig buildRoleConfig(Long roleId,
            SafetyTablePermissionDO table, List<SafetyFieldPermissionDO> fields) {
        SafetyPermissionSaveReqVO.RolePermissionConfig role = new SafetyPermissionSaveReqVO.RolePermissionConfig();
        role.setRoleId(roleId);
        if (table != null) {
            SafetyPermissionSaveReqVO.TablePermission permission = new SafetyPermissionSaveReqVO.TablePermission();
            permission.setPermissionType(table.getPermissionType());
            permission.setWritePolicy(blank(table.getWritePolicy()) ? null : SafetyWritePermissionService.parsePolicy(
                    table.getWritePolicy()));
            permission.setSecurityLevelId(table.getSecurityLevelId());
            role.setTablePermission(permission);
            if (table.getSchemaName() == null) {
                role.setLegacyTablePermissionId(table.getId());
            }
        }
        role.setLegacyFieldPermissionIds(fields.stream().filter(field -> field.getSchemaName() == null)
                .map(SafetyFieldPermissionDO::getId).toList());
        role.setFieldPermissions(fields.stream().map(field -> {
            SafetyPermissionSaveReqVO.FieldPermission permission = new SafetyPermissionSaveReqVO.FieldPermission();
            permission.setFieldName(field.getFieldName());
            permission.setPermissionType(field.getPermissionType());
            permission.setMaskingRuleId(field.getMaskingRuleId());
            permission.setSecurityLevelId(field.getSecurityLevelId());
            return permission;
        }).toList());
        return role;
    }

    /**
     * 校验数据安全权限的权限类型，不满足约束时拒绝当前操作。
     */
    private void validatePermissionTypes(SafetyPermissionSaveReqVO saveReqVO) {
        for (SafetyPermissionSaveReqVO.RolePermissionConfig roleConfig : saveReqVO.getRolePermissionConfigs()) {
            if (roleConfig.getTablePermission() != null
                    && !isValidTablePermission(roleConfig.getTablePermission().getPermissionType())) {
                throw exception(PERMISSION_TYPE_INVALID);
            }
            if (CollUtil.isEmpty(roleConfig.getFieldPermissions())) {
                continue;
            }
            for (SafetyPermissionSaveReqVO.FieldPermission field : roleConfig.getFieldPermissions()) {
                if (!isValidFieldPermission(field.getPermissionType())) {
                    throw exception(PERMISSION_TYPE_INVALID);
                }
            }
        }
    }

    /**
     * 校验数据安全权限的脱敏规则，不满足约束时拒绝当前操作。
     */
    private void validateMaskingRules(SafetyPermissionSaveReqVO saveReqVO) {
        for (SafetyPermissionSaveReqVO.RolePermissionConfig roleConfig : saveReqVO.getRolePermissionConfigs()) {
            if (CollUtil.isEmpty(roleConfig.getFieldPermissions())) {
                continue;
            }
            for (SafetyPermissionSaveReqVO.FieldPermission field : roleConfig.getFieldPermissions()) {
                if (Objects.equals(field.getPermissionType(), SafetyFieldPermissionDO.TYPE_MASK)
                        && (field.getMaskingRuleId() == null || field.getMaskingRuleId() <= 0)) {
                    throw exception(PERMISSION_MASKING_RULE_REQUIRED,
                            StrUtil.blankToDefault(roleConfig.getRoleName(), String.valueOf(roleConfig.getRoleId())),
                            field.getFieldName());
                }
                if (Objects.equals(field.getPermissionType(), SafetyFieldPermissionDO.TYPE_MASK)) {
                    try {
                        ruleService.requireUsableMaskingSpec(field.getMaskingRuleId());
                    } catch (RuntimeException ex) {
                        throw exception(PERMISSION_MASKING_RULE_UNAVAILABLE,
                                StrUtil.blankToDefault(roleConfig.getRoleName(),
                                        String.valueOf(roleConfig.getRoleId())),
                                field.getFieldName());
                    }
                }
            }
        }
    }

    /**
     * 判断字段是否包含需要持久化的限制、脱敏规则或安全等级。
     */
    private static boolean shouldPersistField(SafetyPermissionSaveReqVO.FieldPermission field) {
        if (field == null || StrUtil.isBlank(field.getFieldName())) {
            return false;
        }
        if (!Objects.equals(field.getPermissionType(), SafetyFieldPermissionDO.TYPE_PLAIN)) {
            return true;
        }
        if (field.getMaskingRuleId() != null && field.getMaskingRuleId() > 0) {
            return true;
        }
        return field.getSecurityLevelId() != null;
    }

    /**
     * 判断数据安全权限是否有效表权限。
     */
    private static boolean isValidTablePermission(Integer type) {
        return Objects.equals(type, SafetyTablePermissionDO.TYPE_DENY)
                || Objects.equals(type, SafetyTablePermissionDO.TYPE_READ)
                || Objects.equals(type, SafetyTablePermissionDO.TYPE_READ_WRITE);
    }

    /**
     * 判断数据安全权限是否有效字段权限。
     */
    private static boolean isValidFieldPermission(Integer type) {
        return Objects.equals(type, SafetyFieldPermissionDO.TYPE_DENY)
                || Objects.equals(type, SafetyFieldPermissionDO.TYPE_PLAIN)
                || Objects.equals(type, SafetyFieldPermissionDO.TYPE_MASK);
    }

    /**
     * 读取并校验当前操作人身份。
     */
    private static Long requireActor() {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw rejected("权限变更必须有可信登录用户");
        }
        return actor;
    }

    /**
     * 校验数据安全权限的角色，不满足约束时拒绝当前操作。
     */
    private static void requireRole(Long roleId) {
        if (roleId == null || roleId <= 0) {
            throw exception(PERMISSION_ROLE_REQUIRED);
        }
    }

    /**
     * 检查数据安全权限的变化，不满足约束时拒绝当前操作。
     */
    private static void checkChanged(int rows) {
        if (rows != 1) {
            throw rejected("策略已被并发更新，请重新核验");
        }
    }

}
