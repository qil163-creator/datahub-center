package cn.iocoder.yudao.module.governance.dal.mysql.permission;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.permission.SafetyFieldPermissionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 数据字段访问权限 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyFieldPermissionMapper extends BaseMapperX<SafetyFieldPermissionDO> {

    /**
     * 按表角色查询安全字段权限列表。
     */
    default List<SafetyFieldPermissionDO> selectListByTableRole(Long databaseId, String schemaName, String tableName,
            Long roleId) {
        return selectList(new LambdaQueryWrapperX<SafetyFieldPermissionDO>()
                .eq(SafetyFieldPermissionDO::getDatabaseId, databaseId)
                .eq(SafetyFieldPermissionDO::getSchemaName, schemaName)
                .eq(SafetyFieldPermissionDO::getTableName, tableName)
                .eq(SafetyFieldPermissionDO::getRoleId, roleId)
                .orderByAsc(SafetyFieldPermissionDO::getId));
    }

    /**
     * 按角色编号查询安全字段权限列表。
     */
    default List<SafetyFieldPermissionDO> selectListByRoleId(Long roleId) {
        return selectList(SafetyFieldPermissionDO::getRoleId, roleId);
    }

    /**
     * 按表角色查询有效。
     */
    default List<SafetyFieldPermissionDO> selectActiveByTableRoles(Long databaseId, String schemaName, String tableName,
            Collection<Long> roleIds) {
        return selectList(new LambdaQueryWrapperX<SafetyFieldPermissionDO>()
                .eq(SafetyFieldPermissionDO::getDatabaseId, databaseId)
                .eq(SafetyFieldPermissionDO::getSchemaName, schemaName)
                .eq(SafetyFieldPermissionDO::getTableName, tableName)
                .in(SafetyFieldPermissionDO::getRoleId, roleIds)
                .eq(SafetyFieldPermissionDO::getStatus, 0));
    }

    /**
     * 统计有效未绑定。
     */
    default Long countActiveUnbound(Long databaseId) {
        return selectCount(new LambdaQueryWrapperX<SafetyFieldPermissionDO>()
                .eq(SafetyFieldPermissionDO::getDatabaseId, databaseId)
                .isNull(SafetyFieldPermissionDO::getSchemaName)
                .eq(SafetyFieldPermissionDO::getStatus, 0));
    }

    /**
     * 按指定条件读取并锁定安全字段权限记录，供事务内一致性校验或修改使用。
     */
    default List<SafetyFieldPermissionDO> selectLegacyForUpdate(Long databaseId, String legacyTableName, Long roleId) {
        return selectList(new LambdaQueryWrapperX<SafetyFieldPermissionDO>()
                .eq(SafetyFieldPermissionDO::getDatabaseId, databaseId)
                .eq(SafetyFieldPermissionDO::getTableName, legacyTableName)
                .eq(SafetyFieldPermissionDO::getRoleId, roleId)
                .isNull(SafetyFieldPermissionDO::getSchemaName)
                .orderByAsc(SafetyFieldPermissionDO::getId).last("FOR UPDATE"));
    }

    /**
     * 统计有效脱敏按规则编号。
     */
    default Long countActiveMaskByRuleId(Long ruleId) {
        return selectCount(new LambdaQueryWrapperX<SafetyFieldPermissionDO>()
                .eq(SafetyFieldPermissionDO::getMaskingRuleId, ruleId)
                .eq(SafetyFieldPermissionDO::getPermissionType, SafetyFieldPermissionDO.TYPE_MASK)
                .eq(SafetyFieldPermissionDO::getStatus, 0));
    }

}
