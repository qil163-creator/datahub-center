package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionColumnRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionLegacyBindReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionDatabaseRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionTableRespVO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据权限配置 Service
 *
 * @author Li
 */
public interface SafetyPermissionService {

    /**
     * 保存表与字段权限配置。
     */
    void saveConfig(@Valid SafetyPermissionSaveReqVO saveReqVO);

    /**
     * 读取数据安全权限的配置。
     */
    SafetyPermissionSaveReqVO getConfig(Long databaseId, String tableName, Long roleId);

    /**
     * 获得指定表、角色的权限配置。
     */
    SafetyPermissionSaveReqVO getConfig(Long databaseId, String schemaName, String tableName,
            String tableReference, Long roleId);

    /**
     * 核验后将历史策略绑定到精确物理模式和表，不覆盖或自动合并。
     */
    void bindLegacy(@Valid SafetyPermissionLegacyBindReqVO request);

    /**
     * 获得角色已配置的全部表权限。
     */
    List<SafetyPermissionSaveReqVO> getRolePermissions(Long roleId);

    /**
     * 获得可配置权限的数据源列表。
     */
    List<SafetyPermissionDatabaseRespVO> getDatabaseList();

    /**
     * 获得数据源下的表清单。
     */
    List<SafetyPermissionTableRespVO> getTableList(Long databaseId);

    /**
     * 读取数据安全权限字段列表。
     */
    List<SafetyPermissionColumnRespVO> getColumnList(Long databaseId, String tableName);

    /**
     * 获得表字段列表（附带元数据注释与分级）。
     */
    List<SafetyPermissionColumnRespVO> getColumnList(Long databaseId, String schemaName,
            String tableName, String tableReference);

}
