package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectInstanceMapper;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

/** 列表批量富化展示，不向客户端暴露凭据或执行租约。 */
@Component
public class MetaCollectDisplay {

    @Resource
    private AdminUserApi users;

    @Resource
    private MetaCollectInstanceMapper instances;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectTaskMapper tasks;

    @Resource
    private cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi sources;

    /**
     * 为采集任务批量补充操作人和来源展示信息。
     */
    public List<MetaCollectTaskRespVO> tasks(List<MetaCollectTaskDO> rows) {
        var names = names(rows.stream().flatMap(row -> java.util.stream.Stream.of(row.getCreator(), row.getUpdater(),
                Objects.toString(row.getOwnerUserId(), null))).toList());
        List<Long> ids = rows.stream().map(MetaCollectTaskDO::getLastInstanceId).filter(Objects::nonNull).distinct()
                .toList();
        Map<Long, Integer> status = new HashMap<>();
        if (!ids.isEmpty()) {
            for (var item : instances.selectByIds(ids)) {
                status.put(item.getId(), item.getStatus());
            }
        }
        Map<Long, cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataSourceOptionsDTO.ConnectionOption> connections = new HashMap<>();
        if (!rows.isEmpty()) {
            try {
                sources.options(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId())
                        .getCheckedData().getConnections().forEach(item -> connections.put(item.getId(), item));
            } catch (RuntimeException unavailable) {
                // 未映射的离线导入记录仅使用展示快照，不因此建立或执行连接。
                if (rows.stream()
                        .anyMatch(
                                row -> MetaImportedDisplay.read(row.getDisplaySnapshot()).value("bundleId") == null)) {
                    throw unavailable;
                }
            }
        }
        return rows.stream().map(row -> {
            var vo = BeanUtils.toBean(row, MetaCollectTaskRespVO.class);
            var creator = user(names, row.getCreator());
            var updater = user(names, row.getUpdater());
            if (creator != null) {
                vo.setCreatorName(creator.getNickname());
                vo.setCreatorPhone(creator.getMobile());
            }
            if (updater != null) {
                vo.setUpdaterName(updater.getNickname());
                vo.setUpdaterPhone(updater.getMobile());
            }
            var owner = row.getOwnerUserId() == null ? null : names.get(row.getOwnerUserId());
            if (owner != null) {
                vo.setOwnerName(owner.getNickname());
                vo.setOwnerPhone(owner.getMobile());
            }
            var connection = connections.get(row.getDatasourceId());
            if (connection != null) {
                vo.setDatasourceName(connection.getName());
                vo.setDbType(connection.getDbType());
                vo.setHost(connection.getHost());
                vo.setPort(connection.getPort());
                vo.setUsername(connection.getUsername());
            }
            var imported = MetaImportedDisplay.read(row.getDisplaySnapshot());
            vo.setDatasourceName(imported.fallback(vo.getDatasourceName(), "datasourceName"));
            vo.setDbType(imported.fallback(vo.getDbType(), "dbType"));
            if (owner == null) {
                vo.setOwnerName(imported.fallback(vo.getOwnerName(), "ownerName"));
                vo.setOwnerPhone(imported.fallback(vo.getOwnerPhone(), "ownerPhone"));
            }
            if (creator == null) {
                vo.setCreatorName(imported.fallback(vo.getCreatorName(), "creatorName"));
                vo.setCreatorPhone(imported.fallback(vo.getCreatorPhone(), "creatorPhone"));
            }
            if (updater == null) {
                vo.setUpdaterName(imported.value("updaterName"));
                vo.setUpdaterPhone(imported.value("updaterPhone"));
            }
            vo.setRunStatus(status.get(row.getLastInstanceId()));
            return vo;
        }).toList();
    }

    /**
     * 为采集实例批量补充操作人和来源展示信息。
     */
    public List<MetaCollectInstanceRespVO> instances(List<MetaCollectInstanceDO> rows) {
        var names = names(rows.stream().flatMap(row -> java.util.stream.Stream.of(row.getCreator(),
                Objects.toString(row.getOwnerUserId(), null))).toList());
        Map<Long, String> taskProducts = new HashMap<>();
        List<Long> taskIds = rows.stream().map(MetaCollectInstanceDO::getTaskId).filter(Objects::nonNull).distinct()
                .toList();
        if (!taskIds.isEmpty()) {
            tasks.selectByIds(taskIds).forEach(task -> taskProducts.put(task.getId(), task.getDbType()));
        }
        return rows.stream().map(row -> {
            var vo = BeanUtils.toBean(row, MetaCollectInstanceRespVO.class);
            var creator = user(names, row.getCreator());
            if (creator != null) {
                vo.setCreatorName(creator.getNickname());
                vo.setCreatorPhone(creator.getMobile());
            }
            var owner = row.getOwnerUserId() == null ? null : names.get(row.getOwnerUserId());
            if (owner != null) {
                vo.setOwnerName(owner.getNickname());
                vo.setOwnerPhone(owner.getMobile());
            }
            var imported = MetaImportedDisplay.read(row.getDisplaySnapshot());
            vo.setDatasourceName(imported.fallback(vo.getDatasourceName(), "datasourceName"));
            vo.setDbType(imported.fallback(taskProducts.get(row.getTaskId()), "dbType"));
            if (owner == null) {
                vo.setOwnerName(imported.fallback(vo.getOwnerName(), "ownerName"));
                vo.setOwnerPhone(imported.fallback(vo.getOwnerPhone(), "ownerPhone"));
            }
            if (creator == null) {
                vo.setCreatorName(imported.fallback(vo.getCreatorName(), "creatorName"));
                vo.setCreatorPhone(imported.fallback(vo.getCreatorPhone(), "creatorPhone"));
            }
            return vo;
        }).toList();
    }

    /**
     * 按用户编号建立采集记录的显示名称映射。
     */
    private Map<Long, AdminUserRespDTO> names(List<String> values) {
        Set<Long> ids = new HashSet<>();
        values.forEach(value -> ids.add(id(value)));
        ids.remove(null);
        return ids.isEmpty() ? Map.of() : users.getUserMap(ids);
    }

    /**
     * 将采集记录中的文本标识解析为编号。
     */
    private static Long id(String value) {
        try {
            return Long.valueOf(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 从批量查询结果中读取用户显示名称。
     */
    private static AdminUserRespDTO user(Map<Long, AdminUserRespDTO> names, String value) {
        Long key = id(value);
        return key == null ? null : names.get(key);
    }
}
