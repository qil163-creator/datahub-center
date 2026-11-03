package cn.iocoder.yudao.module.governance.service.metadata.core;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFilePaths;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi;
import cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataSourceOptionsDTO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 跨模块数据源与人员合法性校验，不将连接凭据写入业务对象。 */
@Component
public class MetaCollectSources {

    @Resource
    private MetadataCollectionApi sources;

    @Resource
    private MasterDatabaseResolver databases;

    @Resource
    private AdminUserApi users;

    @Resource
    private DeptApi departments;

    /**
     * 查询元数据采集来源可选项，供配置或关联选择使用。
     */
    public MetadataSourceOptionsDTO options() {
        return sources.options(getLoginUserId()).getCheckedData();
    }

    /**
     * 校验元数据采集来源的与补充，不满足约束时拒绝当前操作。
     */
    public void validateAndEnrich(MetaCollectTaskSaveReqVO req, MetaCollectTaskDO target) {
        var connection = options().getConnections().stream()
                .filter(item -> Objects.equals(item.getId(), req.getDatasourceId()))
                .findFirst().orElseThrow(() -> exception(META_COLLECT_SOURCE_INVALID));
        if (!Objects.equals(connection.getCollectType(), req.getCollectType())) {
            throw exception(META_COLLECT_SOURCE_INVALID);
        }
        target.setDbType(connection.getDbType());
        target.setDatasourceName(connection.getName());
        target.setSourceSystemName(req.getSourceSystemId() == null ? null
                : sources.system(req.getSourceSystemId(), getLoginUserId()).getCheckedData().getName());
        target.setOwnerName(null);
        if (req.getOwnerUserId() != null) {
            users.validateUser(req.getOwnerUserId());
            target.setOwnerName(users.getUser(req.getOwnerUserId()).getCheckedData().getNickname());
        }
        if (req.getOwnerDeptId() != null) {
            departments.validateDeptList(List.of(req.getOwnerDeptId())).getCheckedData();
        }
        if (Objects.equals(req.getCollectType(), 2)) {
            if (req.getFilePath() == null || req.getFilePath().isBlank()) {
                throw exception(META_COLLECT_SCOPE_INVALID);
            }
            try {
                target.setFilePath(RemoteFilePaths.normalize(req.getFilePath()));
            } catch (RuntimeException invalid) {
                throw exception(META_COLLECT_SCOPE_INVALID);
            }
            target.setScopeType(1);
            target.setScopeConfig(null);
            target.setBlacklist(null);
        } else {
            validateScopeSyntax(target);
            if (Objects.equals(req.getScopeType(), 2)) {
                var available = scopeList(req.getDatasourceId());
                var requested = parseScope(target);
                for (var entry : requested.entrySet()) {
                    var db = available.stream().filter(item -> item.getDb().equals(entry.getKey())).findFirst()
                            .orElseThrow(() -> exception(META_COLLECT_SCOPE_INVALID));
                    if (!new HashSet<>(db.getTables()).containsAll(entry.getValue())) {
                        throw exception(META_COLLECT_SCOPE_INVALID);
                    }
                }
            }
        }
    }

    /**
     * 限定范围元数据采集来源的列表。
     */
    public List<cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectScopeRespVO> scopeList(
            Long datasourceId) {
        if (options().getConnections().stream().noneMatch(item -> Objects.equals(item.getId(), datasourceId)
                && Objects.equals(item.getCollectType(), 1))) {
            throw exception(META_COLLECT_SOURCE_INVALID);
        }
        var info = databases.resolve(datasourceId, META_COLLECT_SOURCE_INVALID);
        try (var pool = databases.createDataSource(info)) {
            var reader = new MetaDataByDataSourceServiceImpl(pool, info.getProductType());
            List<cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectScopeRespVO> result = new ArrayList<>();
            var schemas = reader.querySchemaList();
            if (schemas.size() > 100) {
                throw exception(META_COLLECT_SCOPE_INVALID);
            }
            int count = 0;
            for (String schema : schemas) {
                var tables = reader.queryTableList(schema).stream().map(t -> t.getTableName()).toList();
                count += tables.size();
                if (count > 10000) {
                    throw exception(META_COLLECT_SCOPE_INVALID);
                }
                var entry = new cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectScopeRespVO();
                entry.setDb(schema);
                entry.setTables(tables);
                result.add(entry);
            }
            return result;
        }
    }

    /**
     * 校验元数据采集来源的范围语法，不满足约束时拒绝当前操作。
     */
    public static void validateScopeSyntax(MetaCollectTaskDO task) {
        parseScope(task);
        if (task.getBlacklist() != null && !task.getBlacklist().isBlank()) {
            try {
                var root = JsonUtils.getObjectMapper().readTree(task.getBlacklist());
                if (!root.isArray() || root.size() > 100) {
                    throw new IllegalArgumentException();
                }
                for (var value : root) {
                    if (!value.isString() || value.asString().isBlank() || value.asString().length() > 256) {
                        throw new IllegalArgumentException();
                    }
                }
            } catch (RuntimeException invalid) {
                throw exception(META_COLLECT_SCOPE_INVALID);
            }
        }
    }

    /**
     * 解析元数据采集来源的范围。
     */
    public static Map<String, Set<String>> parseScope(MetaCollectTaskDO task) {
        if (Objects.equals(task.getScopeType(), 1)) {
            return null;
        }
        try {
            if (!Objects.equals(task.getScopeType(), 2)) {
                throw new IllegalArgumentException();
            }
            var root = JsonUtils.getObjectMapper().readTree(task.getScopeConfig());
            if (!root.isArray() || root.isEmpty() || root.size() > 100) {
                throw new IllegalArgumentException();
            }
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (var entry : root) {
                if (!entry.isObject() || !entry.path("db").isString() || !entry.path("tables").isArray()) {
                    throw new IllegalArgumentException();
                }
                String db = entry.path("db").asString();
                if (db.isBlank() || db.length() > 256 || db.chars()
                        .anyMatch(Character::isISOControl) || result.containsKey(db)) {
                    throw new IllegalArgumentException();
                }
                Set<String> tables = new LinkedHashSet<>();
                if (entry.path("tables").size() > 10000) {
                    throw new IllegalArgumentException();
                }
                for (var table : entry.path("tables")) {
                    if (!table.isString() || table.asString().isBlank() || table.asString().length() > 256
                            || table.asString().chars().anyMatch(Character::isISOControl)) {
                        throw new IllegalArgumentException();
                    }
                    tables.add(table.asString());
                }
                result.put(db, tables);
            }
            return result;
        } catch (RuntimeException invalid) {
            throw exception(META_COLLECT_SCOPE_INVALID);
        }
    }
}
