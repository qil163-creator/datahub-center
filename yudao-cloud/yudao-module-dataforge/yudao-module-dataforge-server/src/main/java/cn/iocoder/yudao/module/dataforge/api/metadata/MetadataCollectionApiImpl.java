package cn.iocoder.yudao.module.dataforge.api.metadata;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.api.metadata.dto.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.systemsource.SystemSourceDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.systemsource.SystemSourceMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileOperations;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFilePaths;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.fileresource.FileResourceConnectionResolver;
import cn.iocoder.yudao.module.dataforge.framework.security.MetadataCollectionRpcFilter;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

@RestController
public class MetadataCollectionApiImpl implements MetadataCollectionApi {

    @Resource
    private HttpServletRequest servletRequest;

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private DatabaseTypeMapper typeMapper;

    @Resource
    private SystemSourceMapper systemMapper;

    @Resource
    private AccessDatabaseResolver jdbcResolver;

    @Resource
    private PermissionApi permissionApi;

    @Resource
    private FileResourceConnectionResolver files;

    /**
     * 获得启用的采集连接和来源系统，不含凭据。
     *
     * @param actorId 操作人编号
     * @return 元数据来源选项
     */
    @Override
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<MetadataSourceOptionsDTO> options(Long actorId) {
        requirePermission(actorId, "governance:meta-collect-task:query", "governance:meta-file:query");
        List<MetadataSourceOptionsDTO.ConnectionOption> connections = new ArrayList<>();
        for (DatabaseDO db : databaseMapper.selectList(new LambdaQueryWrapperX<DatabaseDO>()
                .eq(DatabaseDO::getStatus, 0).orderByAsc(DatabaseDO::getId))) {
            DatabaseTypeDO type = type(db);
            if (type == null) {
                continue;
            }
            try {
                connections.add(option(db, type));
            } catch (
                    cn.iocoder.yudao.framework.common.exception.ServiceException unsupported) { /* 未安装的产品不作为可采集连接。 */ }
        }
        MetadataSourceOptionsDTO dto = new MetadataSourceOptionsDTO();
        dto.setConnections(connections);
        dto.setSystems(BeanUtils.toBean(systemMapper.selectList(new LambdaQueryWrapperX<SystemSourceDO>()
                        .eq(SystemSourceDO::getStatus, 0).orderByAsc(SystemSourceDO::getId)),
                MetadataSourceOptionsDTO.SystemOption.class));
        return success(dto);
    }

    /**
     * 只读采集远端目录技术元数据，最多 10000 项、32 层。
     *
     * @param id 记录编号
     * @param path 路径
     * @param actorId 操作人编号
     * @return 只读采集远端目录技术元数据，最多 10000 项、32 层结果集合
     */
    @Override
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<List<MetadataFileEntryDTO>> fileManifest(Long id, String path, Long actorId) {
        requirePermission(actorId, "governance:meta-collect-task:run");
        requirePermission(actorId, "governance:meta-collect-task:query");
        String normalized;
        try {
            normalized = RemoteFilePaths.normalize(path);
        } catch (IllegalArgumentException invalid) {
            throw exception(FILE_RESOURCE_PATH_INVALID);
        }
        try (var client = files.open(id)) {
            var entries = RemoteFileOperations.walk(client, normalized, 10000, 32);
            List<MetadataFileEntryDTO> result = new ArrayList<>(entries.size());
            for (var entry : entries) {
                var item = new MetadataFileEntryDTO();
                item.setName(entry.name());
                item.setPath(entry.path());
                item.setDirectory(entry.directory());
                item.setSize(entry.size());
                item.setModifiedTime(entry.modifiedTime());
                result.add(item);
            }
            return success(result);
        } catch (java.io.IOException failure) {
            throw exception(FILE_RESOURCE_OPERATION_FAILED);
        }
    }

    /**
     * 校验启用的来源系统。
     *
     * @param id 记录编号
     * @param actorId 操作人编号
     * @return 系统选项
     */
    @Override
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<MetadataSourceOptionsDTO.SystemOption> system(Long id, Long actorId) {
        requirePermission(actorId, "governance:meta-collect-task:query");
        SystemSourceDO system = systemMapper.selectById(id);
        if (system == null || !Objects.equals(system.getStatus(), (short) 0)) {
            throw exception(SYSTEM_SOURCE_NOT_EXISTS);
        }
        return success(BeanUtils.toBean(system, MetadataSourceOptionsDTO.SystemOption.class));
    }

    /**
     * 校验权限。
     *
     * @param actorId 操作人编号
     * @param permission 权限
     */
    private void requirePermission(Long actorId, String... permission) {
        MetadataCollectionRpcFilter.requireVerified(servletRequest);
        if (actorId == null || actorId <= 0 || !Boolean.TRUE.equals(
                permissionApi.hasAnyPermissions(actorId, permission).getCheckedData())) {
            throw exception(cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN);
        }
    }

    /**
     * 读取元数据集合类型。
     *
     * @param db 数据库
     * @return 数据库类型
     */
    private DatabaseTypeDO type(DatabaseDO db) {
        DatabaseTypeDO type = db.getDatabaseType() == null ? null : typeMapper.selectById(
                db.getDatabaseType().longValue());
        return type != null && Objects.equals(type.getStatus(), (short) 0) ? type : null;
    }

    /**
     * 获取元数据集合的选项。
     *
     * @param db 数据库
     * @param type 类型标识
     * @return 连接选项
     */
    private MetadataSourceOptionsDTO.ConnectionOption option(DatabaseDO db, DatabaseTypeDO type) {
        var dto = new MetadataSourceOptionsDTO.ConnectionOption();
        dto.setId(db.getId());
        dto.setName(db.getName());
        dto.setHost(db.getDatabaseIp());
        dto.setPort(db.getDatabasePort());
        dto.setUsername(db.getUserName());
        boolean file = FileResourceConnectionResolver.supported(type);
        dto.setCollectType(file ? 2 : 1);
        dto.setDbType(file ? type.getTypeCode().toUpperCase(Locale.ROOT) : jdbcResolver.resolveProductType(db).name());
        return dto;
    }
}
