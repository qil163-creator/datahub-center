package cn.iocoder.yudao.module.governance.dal.mysql.permission;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.permission.SafetyTablePermissionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 数据表访问权限 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyTablePermissionMapper extends BaseMapperX<SafetyTablePermissionDO> {

    /**
     * 按表角色查询安全表权限。
     */
    default SafetyTablePermissionDO selectByTableRole(Long databaseId, String schemaName, String tableName,
            Long roleId) {
        return selectOne(new LambdaQueryWrapperX<SafetyTablePermissionDO>()
                .eq(SafetyTablePermissionDO::getDatabaseId, databaseId)
                .eq(SafetyTablePermissionDO::getSchemaName, schemaName)
                .eq(SafetyTablePermissionDO::getTableName, tableName)
                .eq(SafetyTablePermissionDO::getRoleId, roleId));
    }

    /**
     * 按表角色查询安全表权限列表。
     */
    default List<SafetyTablePermissionDO> selectListByTableRole(Long databaseId, String schemaName, String tableName,
            Collection<Long> roleIds) {
        return selectList(new LambdaQueryWrapperX<SafetyTablePermissionDO>()
                .eq(SafetyTablePermissionDO::getDatabaseId, databaseId)
                .eq(SafetyTablePermissionDO::getSchemaName, schemaName)
                .eq(SafetyTablePermissionDO::getTableName, tableName)
                .in(SafetyTablePermissionDO::getRoleId, roleIds)
                .eq(SafetyTablePermissionDO::getStatus, 0));
    }

    /**
     * 按角色编号查询安全表权限列表。
     */
    default List<SafetyTablePermissionDO> selectListByRoleId(Long roleId) {
        return selectList(SafetyTablePermissionDO::getRoleId, roleId);
    }

    /**
     * 统计有效未绑定。
     */
    default Long countActiveUnbound(Long databaseId) {
        return selectCount(new LambdaQueryWrapperX<SafetyTablePermissionDO>()
                .eq(SafetyTablePermissionDO::getDatabaseId, databaseId)
                .isNull(SafetyTablePermissionDO::getSchemaName)
                .eq(SafetyTablePermissionDO::getStatus, 0));
    }

    /** 核验接口锁住明确选中的历史组；普通权限查询不得调用这个裸名入口。 */
    default List<SafetyTablePermissionDO> selectLegacyForUpdate(Long databaseId, String legacyTableName, Long roleId) {
        return selectList(new LambdaQueryWrapperX<SafetyTablePermissionDO>()
                .eq(SafetyTablePermissionDO::getDatabaseId, databaseId)
                .eq(SafetyTablePermissionDO::getTableName, legacyTableName)
                .eq(SafetyTablePermissionDO::getRoleId, roleId)
                .isNull(SafetyTablePermissionDO::getSchemaName)
                .orderByAsc(SafetyTablePermissionDO::getId).last("FOR UPDATE"));
    }

}
