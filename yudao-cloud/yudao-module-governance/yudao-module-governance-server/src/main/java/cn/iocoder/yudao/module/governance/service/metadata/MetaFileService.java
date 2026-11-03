package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFilePaths;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi;
import cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataFileEntryDTO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaFileMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectInstanceMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 平台内具备文件元数据权限的人员可见；仅管理目录技术记录，不修改远端内容。 */
@Service
public class MetaFileService {

    @Resource
    private MetaFileMapper mapper;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper dbs;

    @Resource
    private MetaCollectInstanceMapper instances;

    @Resource
    private MetadataCollectionApi sources;

    @Resource
    private AdminUserApi users;

    @Resource
    private TransactionTemplate transactions;

    /**
     * 获得文件元数据分页。
     */
    public PageResult<MetaFileRespVO> page(MetaFilePageReqVO req) {
        if (req.getFileType() != null) {
            req.setFileType(req.getFileType().isBlank() ? null : req.getFileType().toUpperCase(Locale.ROOT));
        }
        if (req.getPath() != null) {
            req.setPath(RemoteFilePaths.normalize(req.getPath()));
        }
        var page = mapper.selectPage(req);
        return new PageResult<>(enrich(page.getList()), page.getTotal());
    }

    /**
     * 获得文件元数据详情。
     */
    public MetaFileRespVO get(Long id) {
        return enrich(List.of(required(id))).getFirst();
    }

    /**
     * 读取文件元数据并在记录缺失时拒绝当前操作。
     */
    private MetaFileDO required(Long id) {
        var file = mapper.selectById(id);
        if (file == null) {
            throw exception(META_FILE_NOT_EXISTS);
        }
        return file;
    }

    /**
     * 更新业务。
     */
    public void updateBiz(MetaFileUpdateBizReqVO req) {
        required(req.getId());
        for (Long id : Arrays.asList(req.getOwnerUserId(), req.getTechLeaderUserId(), req.getBusinessLeaderUserId())) {
            if (id != null) {
                users.validateUser(id);
            }
        }
        int changed = mapper.update(null, Wrappers.lambdaUpdate(MetaFileDO.class)
                .eq(MetaFileDO::getId, req.getId()).eq(MetaFileDO::getBizRevision, req.getBizRevision())
                .set(MetaFileDO::getBusinessName, req.getBusinessName())
                .set(MetaFileDO::getDescription, req.getDescription())
                .set(MetaFileDO::getOwnerUserId, req.getOwnerUserId())
                .set(MetaFileDO::getTechLeaderUserId, req.getTechLeaderUserId())
                .set(MetaFileDO::getTechLeaderPhone, req.getTechLeaderPhone())
                .set(MetaFileDO::getBusinessLeaderUserId, req.getBusinessLeaderUserId())
                .set(MetaFileDO::getBusinessLeaderPhone, req.getBusinessLeaderPhone())
                .set(MetaFileDO::getBizRevision, req.getBizRevision() + 1)
                .set(MetaFileDO::getUpdater, Objects.toString(
                        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId(),
                        "metadata"))
                .set(MetaFileDO::getUpdateTime, LocalDateTime.now()));
        if (changed != 1) {
            throw exception(META_FILE_CONCURRENT);
        }
    }

    /**
     * 删除文件元数据，不删除远端文件。
     */
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            throw exception(META_FILE_SCOPE_LIMIT);
        }
        transactions.executeWithoutResult(tx -> {
            var entries = ids.stream().distinct().map(this::required).toList();
            entries.stream().map(MetaFileDO::getDatasourceId).distinct().sorted().forEach(mapper::lockDatasource);
            mapper.update(null, Wrappers.lambdaUpdate(MetaFileDO.class).in(MetaFileDO::getId, ids)
                    .set(MetaFileDO::getDeleted, 1)
                    .set(MetaFileDO::getUpdater, Objects.toString(
                            cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId(),
                            "metadata"))
                    .set(MetaFileDO::getUpdateTime, LocalDateTime.now()));
        });
    }

    /** table 系列计数在文件任务中表示文件物理变化，业务属性维护不计入。 */
    public record CollectionResult(int count, int added, int updated, int missing) {

    }

    /**
     * 采集指定文件来源的目录与文件元数据。
     */
    public CollectionResult collect(MetaCollectTaskDO task, MetaCollectInstanceDO instance, Runnable writeFence) {
        // RPC 与远端 IO 完全在事务之外；未完整返回的 manifest 不产生删除结论。
        var entries = sources.fileManifest(task.getDatasourceId(), task.getFilePath(), instance.getExecutionUserId())
                .getCheckedData();
        if (entries == null || entries.size() > 10000) {
            throw exception(META_FILE_SCOPE_LIMIT);
        }
        String root = RemoteFilePaths.normalize(task.getFilePath());
        for (var entry : entries) {
            String path = RemoteFilePaths.normalize(entry.getPath());
            if (!inside(root, path) || !path.equals(entry.getPath()) || entry.getSize() < 0
                    || entry.getName() == null || entry.getName().length() > 255) {
                throw exception(META_COLLECT_SCOPE_INVALID);
            }
        }
        return transactions.execute(tx -> {
            writeFence.run();
            mapper.lockDatasource(task.getDatasourceId());
            var existing = mapper.selectList(new LambdaQueryWrapperX<MetaFileDO>()
                    .eq(MetaFileDO::getDatasourceId, task.getDatasourceId()).last("LIMIT 10001"));
            if (existing.size() > 10000) {
                throw exception(META_FILE_SCOPE_LIMIT);
            }
            Map<String, MetaFileDO> byPath = new HashMap<>();
            existing.forEach(item -> byPath.put(item.getPath(), item));
            long newFiles = entries.stream()
                    .filter(entry -> !entry.isDirectory() && !byPath.containsKey(entry.getPath())).count();
            if (existing.size() + newFiles > 10000) {
                throw exception(META_FILE_SCOPE_LIMIT);
            }
            Set<String> seen = new HashSet<>();
            LocalDateTime now = LocalDateTime.now();
            int count = 0, added = 0, updated = 0, missingCount = 0;
            for (MetadataFileEntryDTO entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (!seen.add(entry.getPath())) {
                    throw exception(META_COLLECT_SCOPE_INVALID);
                }
                MetaFileDO old = byPath.get(entry.getPath());
                MetaFileDO current = MetaFileDO.builder().id(old == null ? null : old.getId())
                        .datasourceId(task.getDatasourceId()).datasourceName(task.getDatasourceName())
                        .sourceSystemId(task.getSourceSystemId()).sourceSystemName(task.getSourceSystemName())
                        .taskId(task.getId()).instanceId(instance.getId()).path(entry.getPath())
                        .parentPath(parent(entry.getPath())).name(entry.getName()).fileType(extension(entry.getName()))
                        .size(entry.getSize()).sourceModifiedTime(entry.getModifiedTime() <= 0 ? null
                                : LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.getModifiedTime()),
                                ZoneId.systemDefault()))
                        .lastSeenTime(now).objectStatus(1).build();
                current.setUpdater(Objects.toString(instance.getExecutionUserId(), "metadata"));
                if (old == null) {
                    added++;
                    current.setCreator(current.getUpdater());
                    current.setFirstSeenTime(now);
                    current.setBizRevision(0);
                    current.setOwnerUserId(task.getOwnerUserId());
                    mapper.insert(current);
                } else {
                    if (!Objects.equals(old.getSize(), current.getSize())
                            || !Objects.equals(old.getSourceModifiedTime(), current.getSourceModifiedTime())
                            || !Objects.equals(old.getName(), current.getName())
                            || !Objects.equals(old.getFileType(), current.getFileType())
                            || !Objects.equals(old.getObjectStatus(), 1)) {
                        updated++;
                    }
                    mapper.updateById(current);
                    mapper.update(null, Wrappers.lambdaUpdate(MetaFileDO.class).eq(MetaFileDO::getId, old.getId())
                            .set(MetaFileDO::getSourceModifiedTime, current.getSourceModifiedTime())
                            .set(MetaFileDO::getSourceSystemId, current.getSourceSystemId())
                            .set(MetaFileDO::getSourceSystemName, current.getSourceSystemName()));
                }
                count++;
            }
            for (var old : existing) {
                if (inside(root, old.getPath()) && !seen.contains(old.getPath()) && !Objects.equals(
                        old.getObjectStatus(), 2)) {
                    missingCount++;
                    var missing = MetaFileDO.builder().id(old.getId()).objectStatus(2).build();
                    missing.setUpdater(Objects.toString(instance.getExecutionUserId(), "metadata"));
                    mapper.updateById(missing);
                }
            }
            // 与元数据提交同一事务持久化累计统计，崩溃恢复重扫不会抹掉已提交的变化数。
            instances.update(null, Wrappers.lambdaUpdate(MetaCollectInstanceDO.class)
                    .eq(MetaCollectInstanceDO::getId, instance.getId())
                    .set(MetaCollectInstanceDO::getFileCount, count)
                    .setSql("table_add=COALESCE(table_add,0)+{0}", added)
                    .setSql("table_update=COALESCE(table_update,0)+{0}", updated)
                    .setSql("table_delete=COALESCE(table_delete,0)+{0}", missingCount)
                    .set(MetaCollectInstanceDO::getUpdater, Objects.toString(instance.getExecutionUserId(), "metadata"))
                    .set(MetaCollectInstanceDO::getUpdateTime, now));
            var stats = instances.selectById(instance.getId());
            return new CollectionResult(count, stats.getTableAdd(), stats.getTableUpdate(), stats.getTableDelete());
        });
    }

    /**
     * 判断目标路径是否仍处于允许的文件根目录内。
     */
    private static boolean inside(String root, String path) {
        return "/".equals(root) || root.equals(path) || path.startsWith(root + "/");
    }

    /**
     * 解析文件路径的父目录标识。
     */
    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    /**
     * 提取文件名中的扩展名。
     */
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String value = dot < 0 || dot == name.length() - 1 ? "FILE" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
        return value.length() > 32 ? "FILE" : value;
    }

    /**
     * 获得已采集目录树。
     */
    public List<MetaFileTreeNodeVO> tree() {
        var files = mapper.selectList(
                new LambdaQueryWrapperX<MetaFileDO>().orderByAsc(MetaFileDO::getDatasourceId, MetaFileDO::getPath)
                        .last("LIMIT 10001"));
        if (files.size() > 10000) {
            throw exception(META_FILE_SCOPE_LIMIT);
        }
        Map<String, MetaFileTreeNodeVO> nodes = new LinkedHashMap<>();
        List<MetaFileTreeNodeVO> roots = new ArrayList<>();
        for (var db : dbs.selectListForTree()) {
            String key = "system:" + Objects.toString(db.getSourceSystemId(), "none");
            nodes.computeIfAbsent(key, ignored -> {
                var node = new MetaFileTreeNodeVO();
                node.setId(key);
                node.setType("system");
                node.setSourceSystemId(db.getSourceSystemId());
                node.setName(Objects.requireNonNullElse(db.getSourceSystemName(), "未关联来源系统"));
                roots.add(node);
                return node;
            });
        }
        for (var file : files) {
            String systemKey = "system:" + Objects.toString(file.getSourceSystemId(), "none");
            var system = nodes.computeIfAbsent(systemKey, key -> {
                var n = node(key, Objects.requireNonNullElse(file.getSourceSystemName(), "未关联来源系统"), "system",
                        file, null);
                roots.add(n);
                return n;
            });
            String connectionKey = systemKey + ":connection:" + file.getDatasourceId();
            var connection = nodes.computeIfAbsent(connectionKey, key -> {
                var n = node(key, file.getDatasourceName(), "connection", file, null);
                system.getChildren().add(n);
                return n;
            });
            var parentNode = connection;
            String path = "";
            for (String segment : file.getParentPath().split("/")) {
                if (segment.isBlank()) {
                    continue;
                }
                path += "/" + segment;
                String key = systemKey + ":directory:" + file.getDatasourceId() + ":" + path;
                var child = nodes.get(key);
                if (child == null) {
                    child = node(key, segment, "directory", file, path);
                    nodes.put(key, child);
                    parentNode.getChildren().add(child);
                }
                parentNode = child;
            }
        }
        roots.sort(Comparator.comparing(MetaFileTreeNodeVO::getSourceSystemId,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return roots;
    }

    /**
     * 组装文件元数据树的目录或文件节点。
     */
    private MetaFileTreeNodeVO node(String id, String name, String type, MetaFileDO file, String path) {
        var node = new MetaFileTreeNodeVO();
        node.setId(id);
        node.setName(name);
        node.setType(type);
        node.setDbType(MetaImportedDisplay.read(file.getDisplaySnapshot()).value("dbType"));
        node.setSourceSystemId(file.getSourceSystemId());
        node.setDatasourceId("system".equals(type) ? null : file.getDatasourceId());
        node.setPath(path);
        return node;
    }

    /**
     * 为文件元数据补充业务与来源显示信息。
     */
    private List<MetaFileRespVO> enrich(List<MetaFileDO> entries) {
        Set<Long> ids = new HashSet<>();
        for (var row : entries) {
            ids.add(row.getOwnerUserId());
            ids.add(row.getTechLeaderUserId());
            ids.add(row.getBusinessLeaderUserId());
            ids.add(userId(row.getCreator()));
            ids.add(userId(row.getUpdater()));
        }
        ids.remove(null);
        Map<Long, AdminUserRespDTO> names = ids.isEmpty() ? Map.of() : users.getUserMap(ids);
        Map<Long, cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataSourceOptionsDTO.ConnectionOption> connections = new HashMap<>();
        if (!entries.isEmpty()) {
            try {
                sources.options(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId())
                        .getCheckedData().getConnections().forEach(item -> connections.put(item.getId(), item));
            } catch (RuntimeException unavailable) {
                // 未映射的导入引用仅保留展示，不将来源身份当作平台连接。
                if (entries.stream()
                        .anyMatch(
                                row -> MetaImportedDisplay.read(row.getDisplaySnapshot()).value("bundleId") == null)) {
                    throw unavailable;
                }
            }
        }
        return entries.stream().map(row -> {
            var vo = BeanUtils.toBean(row, MetaFileRespVO.class);
            var connection = connections.get(row.getDatasourceId());
            if (connection != null) {
                vo.setDbType(connection.getDbType());
                vo.setHost(connection.getHost());
                vo.setPort(connection.getPort());
                vo.setUsername(connection.getUsername());
            }
            vo.setOwnerName(name(names, row.getOwnerUserId()));
            vo.setTechLeaderName(name(names, row.getTechLeaderUserId()));
            vo.setBusinessLeaderName(name(names, row.getBusinessLeaderUserId()));
            vo.setCreatorName(name(names, userId(row.getCreator())));
            vo.setUpdaterName(name(names, userId(row.getUpdater())));
            var tech = row.getTechLeaderUserId() == null ? null : names.get(row.getTechLeaderUserId());
            var owner = row.getOwnerUserId() == null ? null : names.get(row.getOwnerUserId());
            var business = row.getBusinessLeaderUserId() == null ? null : names.get(row.getBusinessLeaderUserId());
            var creator = userId(row.getCreator()) == null ? null : names.get(userId(row.getCreator()));
            var updater = userId(row.getUpdater()) == null ? null : names.get(userId(row.getUpdater()));
            if (tech != null) {
                vo.setTechLeaderPhone(tech.getMobile());
            }
            if (business != null) {
                vo.setBusinessLeaderPhone(business.getMobile());
            }
            var imported = MetaImportedDisplay.read(row.getDisplaySnapshot());
            vo.setDatasourceName(imported.fallback(vo.getDatasourceName(), "datasourceName"));
            vo.setDbType(imported.fallback(vo.getDbType(), "dbType"));
            if (owner == null) {
                vo.setOwnerName(imported.fallback(vo.getOwnerName(), "ownerName"));
            }
            if (tech == null) {
                vo.setTechLeaderName(imported.fallback(vo.getTechLeaderName(), "techLeaderName"));
                vo.setTechLeaderPhone(imported.fallback(vo.getTechLeaderPhone(), "techLeaderPhone"));
            }
            if (business == null) {
                vo.setBusinessLeaderName(imported.fallback(vo.getBusinessLeaderName(), "businessLeaderName"));
                vo.setBusinessLeaderPhone(imported.fallback(vo.getBusinessLeaderPhone(), "businessLeaderPhone"));
            }
            vo.setCreatorName(
                    creator == null ? imported.fallback(vo.getCreatorName(), "creatorName") : creator.getNickname());
            vo.setCreatorPhone(creator == null ? imported.value("creatorPhone") : creator.getMobile());
            vo.setUpdaterName(
                    updater == null ? imported.fallback(vo.getUpdaterName(), "updaterName") : updater.getNickname());
            vo.setUpdaterPhone(updater == null ? imported.value("updaterPhone") : updater.getMobile());
            return vo;
        }).toList();
    }

    /**
     * 读取并校验当前用户编号。
     */
    private static Long userId(String id) {
        try {
            return Long.valueOf(id);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 解析文件元数据的显示名称。
     */
    private static String name(Map<Long, AdminUserRespDTO> users, Long id) {
        var user = id == null ? null : users.get(id);
        return user == null ? null : user.getNickname();
    }
}
