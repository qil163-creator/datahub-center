package cn.iocoder.yudao.module.asset.service.sensitive;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.sensitive.AssetSensitiveListDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.sensitive.AssetSensitiveListMapper;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.dataasset.*;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.security.SensitiveClassificationReferenceApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

/** 分类、字段和身份查询在事务外完成；写入交给本地事务组件重新核验范围与修订号。 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AssetSensitiveListService {

    @Resource
    private AssetSensitiveListMapper mapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private AssetImportDatasetMapper datasetMapper;

    @Resource
    private AssetCatalogService catalog;

    @Resource
    private SensitiveClassificationReferenceApi safety;

    @Resource
    private HttpServletRequest servletRequest;

    @Resource
    private SecurityProperties securityProperties;

    @Resource
    private AssetSensitiveListWriter writer;

    @Resource
    private AssetUserNameResolver names;

    record Prepared(DataAssetDO asset, Long columnId, String columnName, Integer datasetRevision,
                    SafetyClassificationRespDTO category) {

    }

    /**
     * 在资产维护范围内分页查询敏感清单。
     */
    public PageResult<AssetSensitiveRespVO> page(AssetSensitivePageReqVO req) {
        var auth = catalog.prepareMaintenanceAuthorization();
        List<Long> categories = descendants(req.getCategoryKey());
        if (categories != null && categories.isEmpty()) {
            return PageResult.empty();
        }
        var page = mapper.page(req, categories, auth.getUserId(), auth.getDeptId(),
                auth.isOperator() || auth.isSuperAdmin());
        return new PageResult<>(describe(page.getList()), page.getTotal());
    }

    /**
     * 查询敏感清单详情。
     */
    public AssetSensitiveRespVO get(Long id) {
        var auth = catalog.prepareMaintenanceAuthorization();
        var row = require(id);
        authorize(row.getAssetId(), auth);
        return describe(List.of(row)).getFirst();
    }

    /**
     * 创建敏感清单记录，默认禁用。
     */
    public Long create(AssetSensitiveSaveReqVO req) {
        var auth = catalog.prepareMaintenanceAuthorization();
        return writer.create(req, prepare(req.getAssetId(), req.getColumnId(), req.getCategoryId(), auth), auth);
    }

    /**
     * 修改禁用状态的敏感清单记录。
     */
    public void update(AssetSensitiveSaveReqVO req) {
        if (req.getId() == null || req.getRevision() == null) {
            throw exception(ASSET_SENSITIVE_INPUT_INVALID);
        }
        var auth = catalog.prepareMaintenanceAuthorization();
        var previous = require(req.getId());
        authorize(previous.getAssetId(), auth);
        if (!Objects.equals(previous.getStatus(), 1)) {
            throw exception(ASSET_SENSITIVE_ENABLED);
        }
        var prepared = prepare(req.getAssetId(), req.getColumnId(), req.getCategoryId(), auth);
        writer.update(req, previous.getAssetId(), prepared, auth);
    }

    /**
     * 变更资产敏感字段列表的状态。
     */
    public void changeStatus(AssetSensitiveStatusReqVO req) {
        var auth = catalog.prepareMaintenanceAuthorization();
        var row = require(req.getId());
        authorize(row.getAssetId(), auth);
        Prepared prepared = Objects.equals(req.getStatus(), 0)
                ? prepare(row.getAssetId(), row.getColumnId(), row.getCategoryId(), auth) : null;
        writer.changeStatus(req, row.getAssetId(), prepared, auth);
    }

    /**
     * 删除禁用状态的敏感清单记录。
     */
    public AssetSensitiveOptionVO.Deleted delete(List<AssetSensitiveDeleteReqVO.Item> items, boolean strict) {
        var auth = catalog.prepareMaintenanceAuthorization();
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw exception(ASSET_SENSITIVE_INPUT_INVALID);
        }
        var assets = new TreeMap<Long, Long>();
        for (var item : items) {
            if (item == null || item.getId() == null || item.getRevision() == null
                    || assets.containsKey(item.getId())) {
                throw exception(ASSET_SENSITIVE_INPUT_INVALID);
            }
            var row = require(item.getId());
            authorize(row.getAssetId(), auth);
            assets.put(item.getId(), row.getAssetId());
        }
        return writer.delete(items, assets, strict, auth);
    }

    /**
     * 查询治理侧分类目录及叶分类。
     */
    public List<AssetSensitiveOptionVO.Category> categories() {
        catalog.prepareMaintenanceAuthorization();
        return safety.getClassifications(authorization()).getCheckedData().stream().map(node -> new AssetSensitiveOptionVO.Category(
                node.getId(), node.getParentId(), node.getCategoryId() == null ? "DIRECTORY" : "CATEGORY",
                node.getCategoryId(), node.getName(), Boolean.TRUE.equals(node.getSelectable()),
                node.getStatus() == null ? null : node.getStatus().intValue(),
                node.getLevelName(), node.getLevelColor(), node.getRuleName())).toList();
    }

    /**
     * 查询当前用户可维护的资产选项。
     */
    public PageResult<AssetSensitiveOptionVO.Asset> assetOptions(AssetSensitivePageReqVO req) {
        var auth = catalog.prepareMaintenanceAuthorization();
        var query = new DataAssetPageReqVO();
        query.setKeyword(req.getKeyword());
        query.setPageNo(req.getPageNo());
        query.setPageSize(Math.min(100, req.getPageSize()));
        var page = auth.isOperator() || auth.isSuperAdmin() ? assetMapper.selectPage(query, (Collection<Long>) null,
                null)
                : assetMapper.selectMapPage(query, null, null, auth.getUserId(), auth.getDeptId());
        return new PageResult<>(page.getList().stream().map(asset -> new AssetSensitiveOptionVO.Asset(
                asset.getId(), asset.getAssetName(), asset.getTableName(), asset.getDescription(),
                asset.getAssetType())).toList(), page.getTotal());
    }

    /**
     * 读取指定资产可选择的字段信息。
     */
    public List<AssetSensitiveOptionVO.Column> columnOptions(Long assetId) {
        var auth = catalog.prepareMaintenanceAuthorization();
        authorize(assetId, auth);
        return catalog.listColumns(assetId, null).stream().map(column -> new AssetSensitiveOptionVO.Column(
                column.getId(), column.getColumnName(), column.getCustomComment() == null
                ? column.getSourceComment() : column.getCustomComment(), column.getDataType())).toList();
    }

    /**
     * 核验资产维护权限、字段及启用分类，准备敏感清单写入事实。
     */
    private Prepared prepare(Long assetId, Long columnId, Long categoryId, AssetMaintenanceAuthorization auth) {
        var asset = authorize(assetId, auth);
        MetadataColumnRespDTO column = catalog.listColumns(assetId, null).stream()
                .filter(item -> Objects.equals(item.getId(), columnId)).findFirst()
                .orElseThrow(() -> exception(ASSET_SENSITIVE_COLUMN_INVALID));
        var category = safety.getClassification(categoryId, authorization()).getCheckedData();
        if (category == null || !Objects.equals(category.getCategoryId(), categoryId)
                || !Boolean.TRUE.equals(category.getSelectable()) || !Objects.equals(category.getStatus(), (short) 0)) {
            throw exception(ASSET_SENSITIVE_CATEGORY_INVALID);
        }
        var dataset = datasetMapper.selectById(assetId);
        return new Prepared(asset, column.getId(), column.getColumnName(),
                dataset == null ? null : dataset.getRevision(), category);
    }

    /**
     * 读取资产并校验当前维护授权，拒绝无权维护的资产。
     */
    private DataAssetDO authorize(Long assetId, AssetMaintenanceAuthorization auth) {
        var asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        catalog.assertCanMaintain(asset, auth);
        return asset;
    }

    /**
     * 读取并校验必要的资产敏感字段列表上下文或记录。
     */
    private AssetSensitiveListDO require(Long id) {
        var row = id == null ? null : mapper.selectById(id);
        if (row == null) {
            throw exception(ASSET_SENSITIVE_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 查询指定资产敏感字段列表及其全部后代编号。
     */
    private List<Long> descendants(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        var nodes = safety.getClassifications(authorization()).getCheckedData();
        var keys = new HashSet<String>();
        keys.add(key);
        boolean changed;
        do {
            changed = false;
            for (var node : nodes) {
                if (keys.contains(node.getParentId())) {
                    changed |= keys.add(node.getId());
                }
            }
        }
        while (changed);
        return nodes.stream().filter(node -> keys.contains(node.getId()) && node.getCategoryId() != null)
                .map(SafetyClassificationRespDTO::getCategoryId).distinct().toList();
    }

    /** 仅传播当前请求携带的访问令牌，由治理端重新核验用户及敏感清单权限。 */
    private String authorization() {
        String token = servletRequest == null ? null : SecurityFrameworkUtils.obtainAuthorization(servletRequest,
                securityProperties.getTokenHeader(), securityProperties.getTokenParameter());
        if (token == null || token.isBlank()) {
            throw exception(UNAUTHORIZED);
        }
        return "Bearer " + token;
    }

    /**
     * 组装资产敏感字段列表的描述信息。
     */
    private List<AssetSensitiveRespVO> describe(List<AssetSensitiveListDO> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var assets = new HashMap<Long, DataAssetDO>();
        assetMapper.selectByIds(rows.stream().map(AssetSensitiveListDO::getAssetId).distinct().toList())
                .forEach(asset -> assets.put(asset.getId(), asset));
        var actors = new ArrayList<String>();
        rows.forEach(row -> {
            actors.add(row.getCreator());
            actors.add(row.getUpdater());
        });
        var resolved = names.resolveByTexts(actors);
        return rows.stream().map(row -> {
            var view = BeanUtils.toBean(row, AssetSensitiveRespVO.class);
            var asset = assets.get(row.getAssetId());
            if (asset != null) {
                view.setAssetName(asset.getAssetName());
                view.setAssetDescription(asset.getDescription());
                view.setTableName(asset.getTableName());
            }
            view.setCreatorName(row.getSourceCreatorName() != null ? row.getSourceCreatorName() : names.nameOf(resolved,
                    row.getCreator()));
            view.setUpdaterName(row.getSourceUpdaterName() != null ? row.getSourceUpdaterName() : names.nameOf(resolved,
                    row.getUpdater()));
            return view;
        }).toList();
    }
}
