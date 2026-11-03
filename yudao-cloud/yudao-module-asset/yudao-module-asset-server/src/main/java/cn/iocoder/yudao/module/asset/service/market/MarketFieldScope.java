package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_FIELD_SCOPE_INVALID;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_SCOPE_UNCONFIRMED;

/**
 * 申请表单与批准证据使用不同语义：表单空白可选全列，批准证据永远是明确的物理列集合。
 */
public final class MarketFieldScope {

    public enum Mode {
        ALL, EMPTY, LIST
    }

    private final Mode mode;

    private final Set<String> columns;

    private final boolean physical;

    /**
     * 使用给定模式、字段初始化批准字段范围。
     */
    private MarketFieldScope(Mode mode, Set<String> columns) {
        this(mode, columns, false);
    }

    /**
     * 使用给定模式、字段、物理初始化批准字段范围。
     */
    private MarketFieldScope(Mode mode, Set<String> columns, boolean physical) {
        this.mode = mode;
        this.columns = columns;
        this.physical = physical;
    }

    /**
     * 已提交申请只能使用冻结证据，不得回退原申请或当前元数据，也不得重新分割物理标识符。
     */
    public static MarketFieldScope effectiveScope(MarketResourceApplyDO apply) {
        if (apply == null || apply.getSourceWorkOrderId() != null) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        return new MarketFieldScope(Mode.LIST, requireFrozenColumns(apply.getSnapshot()), true);
    }

    /** 仅合并已经核验的物理字段集合，禁止混入表单 ALL、显示名或重新拆分标识符。 */
    public static MarketFieldScope unionApproved(Collection<MarketFieldScope> scopes) {
        Set<String> columns = new LinkedHashSet<>();
        for (MarketFieldScope scope : scopes) {
            if (scope == null || scope.mode != Mode.LIST || !scope.physical) {
                throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
            }
            columns.addAll(scope.columns);
        }
        if (columns.isEmpty()) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        return new MarketFieldScope(Mode.LIST, Collections.unmodifiableSet(columns), true);
    }

    /** 停止新申请保留已发布合同；停止供数、退役或从未发布的目录不能承载存量消费。 */
    public static boolean supportsExistingGrant(DataAssetDO asset) {
        if (asset == null || Boolean.TRUE.equals(asset.getDeleted()) || Boolean.TRUE.equals(
                asset.getRetirementRequested())) {
            return false;
        }
        if (Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
            return true;
        }
        return Objects.equals(asset.getStatus(), DataAssetDO.STATUS_UNPUBLISHED)
                && StrUtil.isNotBlank(asset.getPublishedVersion())
                && asset.getPublishedSnapshotId() != null && asset.getPublishedSnapshotId() > 0
                && asset.getPublishedCatalogVersionId() != null && asset.getPublishedCatalogVersionId() > 0;
    }

    /** 冻结证据不能套用到另一个资产、发布版本或物理表；旧记录证据不完整时必须重新申请。 */
    public static MarketFieldScope requireMatchesAsset(MarketResourceApplyDO apply, DataAssetDO asset) {
        MarketFieldScope scope = effectiveScope(apply);
        Map<String, Object> snapshot = snapshot(apply.getSnapshot());
        if (asset == null || asset.getId() == null || !Objects.equals(asset.getId(), apply.getAssetId())) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        requireEqual(snapshot, "assetId", asset.getId());
        requireEqual(snapshot, "assetVersion", asset.getPublishedVersion());
        requireEqual(snapshot, "catalogVersionId", asset.getPublishedCatalogVersionId());
        requireEqual(snapshot, "publishedSnapshotId", asset.getPublishedSnapshotId());
        if (Objects.equals(asset.getAssetType(), DataAssetDO.TYPE_DATABASE_TABLE)) {
            if (asset.getDatasourceId() == null || StrUtil.isBlank(asset.getSchemaName())
                    || StrUtil.isBlank(asset.getTableName())) {
                throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
            }
            requireEqual(snapshot, "databaseId", asset.getDatasourceId());
            requireEqual(snapshot, "schemaName", asset.getSchemaName());
            requireEqual(snapshot, "tableName", asset.getTableName());
        }
        return scope;
    }

    /**
     * 校验批准字段范围的相等，不满足约束时拒绝当前操作。
     */
    private static void requireEqual(Map<String, Object> snapshot, String key, Object expected) {
        if (!snapshot.containsKey(key)
                || !Objects.equals(snapshot.get(key), expected == null ? "" : String.valueOf(expected))) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
    }

    /**
     * 读取已批准申请的冻结字段：这是批准范围的唯一严格入口。
     *
     * <p>缺少快照、字段键不存在、集合为空、包含通配符或空白项，都表示没有可信的批准证据，
     * 一律抛出业务异常交人工复核，禁止回退为“全部字段”或当前元数据。
     *
     * @return 批准当时冻结的物理列（保留原始大小写），不可修改
     */
    public static Set<String> requireFrozenColumns(String snapshotJson) {
        Object columns = snapshot(snapshotJson).get("frozenColumns");
        if (!(columns instanceof List<?> values)) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        Set<String> frozen = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String column) || StrUtil.isBlank(column) || "*".equals(column)
                    || !frozen.add(column)) {
                throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
            }
        }
        if (frozen.isEmpty()) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        return Collections.unmodifiableSet(frozen);
    }

    /**
     * 解析批准字段范围冻结快照。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(String snapshotJson) {
        if (StrUtil.isBlank(snapshotJson)) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        try {
            Map<String, Object> body = JsonUtils.parseObject(snapshotJson, Map.class);
            if (body != null) {
                return body;
            }
        } catch (RuntimeException ignored) {
            throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
        }
        throw exception(MARKET_APPLY_SCOPE_UNCONFIRMED);
    }

    /**
     * 解析申请表单字段范围，空白表示全列，显式字段按不区分大小写的名称去重。
     */
    public static MarketFieldScope parse(String fieldScope) {
        if (StrUtil.isBlank(fieldScope)) {
            return new MarketFieldScope(Mode.ALL, Set.of());
        }
        Set<String> result = new LinkedHashSet<>();
        for (String token : fieldScope.split("[,;，、\\s\\n]+")) {
            String column = StrUtil.trim(token);
            if (StrUtil.isNotBlank(column)) {
                result.add(column.toLowerCase(Locale.ROOT));
            }
        }
        if (result.isEmpty()) {
            return new MarketFieldScope(Mode.EMPTY, Set.of());
        }
        return new MarketFieldScope(Mode.LIST, Collections.unmodifiableSet(result));
    }

    /**
     * 返回批准字段范围的模式。
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * 返回批准字段范围的字段。
     */
    public Set<String> getColumns() {
        return columns;
    }

    /**
     * 判断候选字段是否属于范围，冻结物理字段按原始标识符精确匹配。
     */
    public boolean allows(String... names) {
        if (mode == Mode.ALL) {
            return true;
        }
        if (mode == Mode.EMPTY) {
            return false;
        }
        for (String name : names) {
            if (StrUtil.isNotBlank(name) && columns.contains(physical ? name : name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将字段范围写入预览请求，并拒绝引用范围外字段的筛选条件。
     */
    public static void applyToPreviewRequest(DatabasePreviewReqDTO request, MarketFieldScope scope) {
        if (scope == null || scope.mode == Mode.EMPTY) {
            throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
        }
        if (request == null) {
            return;
        }
        if (scope.mode == Mode.LIST) {
            request.setColumns(new ArrayList<>(scope.columns));
        }
        if (scope.mode == Mode.ALL || CollUtil.isEmpty(request.getFilters())) {
            return;
        }
        for (DatabasePreviewReqDTO.Filter filter : request.getFilters()) {
            if (filter != null && StrUtil.isNotBlank(filter.getColumn())
                    && !scope.allows(filter.getColumn())) {
                throw exception(MARKET_APPLY_FIELD_SCOPE_INVALID);
            }
        }
    }
}
