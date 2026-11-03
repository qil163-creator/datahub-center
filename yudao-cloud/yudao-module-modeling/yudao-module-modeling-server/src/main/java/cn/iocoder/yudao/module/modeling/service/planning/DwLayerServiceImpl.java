package cn.iocoder.yudao.module.modeling.service.planning;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerListReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerPrefixPreviewRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DwLayerMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.service.model.ModelTableNameResolver;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 数仓分层 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DwLayerServiceImpl implements DwLayerService {

    /**
     * 前缀预览最多展示的物理模型条数，超过则标记 truncated
     */
    static final int PREFIX_PREVIEW_ITEM_LIMIT = 100;

    @Resource
    private DwLayerMapper dwLayerMapper;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.ModelPlanningReferenceGuard references;

    @Resource
    private LogicModelMapper logicModelMapper;

    @Resource
    private ModelTableNameResolver tableNameResolver;

    /**
     * 创建数仓分层
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createDwLayer(DwLayerSaveReqVO createReqVO) {
        normalizeNodeFields(createReqVO);
        // 校验父级ID，0表示一级分类节点的有效性
        validateParentDwLayer(null, createReqVO.getParentId());
        // 校验层级，仅允许两级结构
        validateDwLayerLevel(createReqVO.getParentId());
        // 校验分层名称的唯一性
        validateDwLayerLayerNameUnique(null, createReqVO.getParentId(), createReqVO.getLayerName());
        // 修复历史数据中分类节点误写入的分层编码
        repairParentCategoryLayerFields(createReqVO.getParentId());
        // 校验分层编码的唯一性
        validateDwLayerLayerCode(createReqVO.getNodeType(), createReqVO.getLayerCode());
        validateDwLayerLayerCodeUnique(null, createReqVO.getLayerCode());

        // 插入
        DwLayerDO dwLayer = BeanUtils.toBean(createReqVO, DwLayerDO.class);
        dwLayerMapper.insert(dwLayer);

        // 返回
        return dwLayer.getId();
    }

    /**
     * 更新数仓分层
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateDwLayer(DwLayerSaveReqVO updateReqVO) {
        if (updateReqVO.getId() == null) {
            throw exception(DW_LAYER_NOT_EXISTS);
        }
        DwLayerDO existing = dwLayerMapper.selectById(updateReqVO.getId());
        if (existing == null) {
            throw exception(DW_LAYER_NOT_EXISTS);
        }
        // 合并数据库原值，兼容列表等场景的局部更新
        fillMissingUpdateFields(updateReqVO, existing);
        normalizeNodeFields(updateReqVO);
        // 校验父级ID，0表示一级分类节点的有效性
        validateParentDwLayer(updateReqVO.getId(), updateReqVO.getParentId());
        // 校验层级，仅允许两级结构
        validateDwLayerLevel(updateReqVO.getParentId());
        // 校验分层名称的唯一性
        validateDwLayerLayerNameUnique(updateReqVO.getId(), updateReqVO.getParentId(), updateReqVO.getLayerName());
        // 校验分层编码的唯一性
        validateDwLayerLayerCode(updateReqVO.getNodeType(), updateReqVO.getLayerCode());
        validateDwLayerLayerCodeUnique(updateReqVO.getId(), updateReqVO.getLayerCode());

        if (!Objects.equals(existing.getNodeType(), updateReqVO.getNodeType())
                || !Objects.equals(existing.getLayerCode(), updateReqVO.getLayerCode())
                || !Objects.equals(existing.getTablePrefix(), updateReqVO.getTablePrefix())
                || !Objects.equals(existing.getDefaultTableType(), updateReqVO.getDefaultTableType())
                || !Objects.equals(existing.getStatus(), updateReqVO.getStatus())) {
            references.requireLayerUnused(updateReqVO.getId());
        }
        // 更新
        DwLayerDO updateObj = BeanUtils.toBean(updateReqVO, DwLayerDO.class);
        dwLayerMapper.updateById(updateObj);
        if (DwLayerDO.NODE_TYPE_CATEGORY.equals(updateReqVO.getNodeType())) {
            dwLayerMapper.clearLayerOnlyFields(updateReqVO.getId());
        }
    }

    /**
     * 更新数仓分层状态
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    @Override
    public void updateDwLayerStatus(Long id, Integer status) {
        validateDwLayerExists(id);
        if (status != null && status != 0) {
            references.requireLayerUnused(id);
        }
        DwLayerDO updateObj = new DwLayerDO();
        updateObj.setId(id);
        updateObj.setStatus(status.shortValue());
        dwLayerMapper.updateById(updateObj);
    }

    /**
     * 补充缺失更新字段。
     *
     * @param reqVO 请求参数
     * @param existing 已有
     */
    private void fillMissingUpdateFields(DwLayerSaveReqVO reqVO, DwLayerDO existing) {
        if (reqVO.getParentId() == null) {
            reqVO.setParentId(existing.getParentId());
        }
        if (StrUtil.isBlank(reqVO.getAncestors())) {
            reqVO.setAncestors(existing.getAncestors());
        }
        if (reqVO.getLevelNo() == null) {
            reqVO.setLevelNo(existing.getLevelNo());
        }
        if (StrUtil.isBlank(reqVO.getLayerName())) {
            reqVO.setLayerName(existing.getLayerName());
        }
        if (StrUtil.isBlank(reqVO.getNodeType())) {
            reqVO.setNodeType(existing.getNodeType());
        }
        if (reqVO.getSortOrder() == null) {
            reqVO.setSortOrder(existing.getSortOrder());
        }
        if (reqVO.getIsBuiltin() == null) {
            reqVO.setIsBuiltin(existing.getIsBuiltin());
        }
        if (reqVO.getStatus() == null) {
            reqVO.setStatus(existing.getStatus());
        }
        if (StrUtil.isBlank(reqVO.getLayerCode())) {
            reqVO.setLayerCode(existing.getLayerCode());
        }
        if (StrUtil.isBlank(reqVO.getTablePrefix())) {
            reqVO.setTablePrefix(existing.getTablePrefix());
        }
        if (StrUtil.isBlank(reqVO.getDefaultTableType())) {
            reqVO.setDefaultTableType(existing.getDefaultTableType());
        }
    }

    /**
     * 分类节点不应保存分层编码等字段，避免与二级分层编码冲突
     */
    private void normalizeNodeFields(DwLayerSaveReqVO reqVO) {
        if (!DwLayerDO.NODE_TYPE_CATEGORY.equals(reqVO.getNodeType())) {
            return;
        }
        reqVO.setLayerCode(null);
        reqVO.setTablePrefix(null);
        reqVO.setDefaultTableType(null);
    }

    /**
     * 修复父级分类分层字段。
     *
     * @param parentId 父级编号
     */
    private void repairParentCategoryLayerFields(Long parentId) {
        if (parentId == null || DwLayerDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        DwLayerDO parent = dwLayerMapper.selectById(parentId);
        if (parent == null || !DwLayerDO.NODE_TYPE_CATEGORY.equals(parent.getNodeType())) {
            return;
        }
        if (StrUtil.isNotBlank(parent.getLayerCode())
                || StrUtil.isNotBlank(parent.getTablePrefix())
                || StrUtil.isNotBlank(parent.getDefaultTableType())) {
            dwLayerMapper.clearLayerOnlyFields(parentId);
        }
    }

    /**
     * 校验数仓分层分层编码。
     *
     * @param nodeType 节点类型
     * @param layerCode 分层编码
     */
    private void validateDwLayerLayerCode(String nodeType, String layerCode) {
        if (!DwLayerDO.NODE_TYPE_LAYER.equals(nodeType)) {
            return;
        }
        if (StrUtil.isBlank(layerCode)) {
            throw exception(DW_LAYER_LAYER_CODE_REQUIRED);
        }
    }

    /**
     * 校验数仓分层分层编码唯一性。
     *
     * @param id 记录编号
     * @param layerCode 分层编码
     */
    private void validateDwLayerLayerCodeUnique(Long id, String layerCode) {
        if (StrUtil.isBlank(layerCode)) {
            return;
        }
        DwLayerDO dwLayer = dwLayerMapper.selectByLayerCode(layerCode);
        if (dwLayer == null) {
            return;
        }
        if (id == null) {
            throw exception(DW_LAYER_LAYER_CODE_DUPLICATE);
        }
        if (!Objects.equals(dwLayer.getId(), id)) {
            throw exception(DW_LAYER_LAYER_CODE_DUPLICATE);
        }
    }

    /**
     * 删除数仓分层
     *
     * @param id 记录编号
     */
    @Override
    public void deleteDwLayer(Long id) {
        references.requireLayerUnused(id);
        // 校验存在
        DwLayerDO dwLayer = dwLayerMapper.selectById(id);
        if (dwLayer == null) {
            throw exception(DW_LAYER_NOT_EXISTS);
        }
        // 校验内置节点不允许删除
        if (DwLayerDO.IS_BUILTIN_YES.equals(dwLayer.getIsBuiltin())) {
            throw exception(DW_LAYER_BUILTIN_CANNOT_DELETE);
        }
        // 校验是否有子数仓分层
        if (dwLayerMapper.selectCountByParentId(id) > 0) {
            throw exception(DW_LAYER_EXITS_CHILDREN);
        }
        // 删除
        dwLayerMapper.deleteById(id);
    }


    /**
     * 校验数仓分层存在性。
     *
     * @param id 记录编号
     */
    private void validateDwLayerExists(Long id) {
        if (dwLayerMapper.selectById(id) == null) {
            throw exception(DW_LAYER_NOT_EXISTS);
        }
    }

    /**
     * 校验数仓分层层级。
     *
     * @param parentId 父级编号
     */
    private void validateDwLayerLevel(Long parentId) {
        if (parentId == null || DwLayerDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        DwLayerDO parentDwLayer = dwLayerMapper.selectById(parentId);
        if (parentDwLayer == null) {
            return;
        }
        if (DwLayerDO.NODE_TYPE_LAYER.equals(parentDwLayer.getNodeType())) {
            throw exception(DW_LAYER_LEVEL_EXCEED_LIMIT);
        }
    }

    /**
     * 校验父级数仓分层。
     *
     * @param id 记录编号
     * @param parentId 父级编号
     */
    private void validateParentDwLayer(Long id, Long parentId) {
        if (parentId == null || DwLayerDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父数仓分层
        if (Objects.equals(id, parentId)) {
            throw exception(DW_LAYER_PARENT_ERROR);
        }
        // 2. 父数仓分层不存在
        DwLayerDO parentDwLayer = dwLayerMapper.selectById(parentId);
        if (parentDwLayer == null) {
            throw exception(DW_LAYER_PARENT_NOT_EXITS);
        }
        // 3. 递归校验父数仓分层，如果父数仓分层是自己的子数仓分层，则报错，避免形成环路
        if (id == null) { // id 为空，说明新增，不需要考虑环路
            return;
        }
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            // 3.1 校验环路
            parentId = parentDwLayer.getParentId();
            if (Objects.equals(id, parentId)) {
                throw exception(DW_LAYER_PARENT_IS_CHILD);
            }
            // 3.2 继续递归下一级父数仓分层
            if (parentId == null || DwLayerDO.PARENT_ID_ROOT.equals(parentId)) {
                break;
            }
            parentDwLayer = dwLayerMapper.selectById(parentId);
            if (parentDwLayer == null) {
                break;
            }
        }
    }

    /**
     * 校验数仓分层分层名称唯一性。
     *
     * @param id 记录编号
     * @param parentId 父级编号
     * @param layerName 分层名称
     */
    private void validateDwLayerLayerNameUnique(Long id, Long parentId, String layerName) {
        DwLayerDO dwLayer = dwLayerMapper.selectByParentIdAndLayerName(parentId, layerName);
        if (dwLayer == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的数仓分层
        if (id == null) {
            throw exception(DW_LAYER_LAYER_NAME_DUPLICATE);
        }
        if (!Objects.equals(dwLayer.getId(), id)) {
            throw exception(DW_LAYER_LAYER_NAME_DUPLICATE);
        }
    }

    /**
     * 获得数仓分层
     *
     * @param id 记录编号
     * @return 数仓分层
     */
    @Override
    public DwLayerDO getDwLayer(Long id) {
        return dwLayerMapper.selectById(id);
    }

    /**
     * 获得数仓分层列表
     *
     * @param listReqVO 列表请求参数
     * @return 数仓分层列表结果集合
     */
    @Override
    public List<DwLayerDO> getDwLayerList(DwLayerListReqVO listReqVO) {
        return dwLayerMapper.selectList(listReqVO);
    }

    // ==================== 表前缀修改预览（只读） ====================

    /**
     * 预览修改表前缀后分层下各物理模型的候选表名及差异
     *
     *  纯只读：不改名、不写库。候选前缀为空串表示去掉前缀；非空时按表英文名规则归一化并校验。
     *
     * @param id 记录编号
     * @param tablePrefix 表前缀
     * @return 数仓分层前缀预览响应数据
     */
    @Override
    public DwLayerPrefixPreviewRespVO previewTablePrefix(Long id, String tablePrefix) {
        DwLayerDO layer = dwLayerMapper.selectById(id);
        if (layer == null) {
            throw exception(DW_LAYER_NOT_EXISTS);
        }
        String currentPrefix = normalizePrefix(layer.getTablePrefix());
        String candidatePrefix = normalizePrefix(tablePrefix);
        if (!candidatePrefix.isEmpty()) {
            // 前缀与表英文名共用一套标识符规则：小写字母开头，仅小写字母、数字、下划线
            tableNameResolver.validateEnglishName(candidatePrefix);
        }

        DwLayerPrefixPreviewRespVO resp = new DwLayerPrefixPreviewRespVO();
        resp.setLayerId(layer.getId());
        resp.setLayerCode(layer.getLayerCode());
        resp.setLayerName(layer.getLayerName());
        resp.setNodeType(layer.getNodeType());
        resp.setCurrentPrefix(currentPrefix);
        resp.setCandidatePrefix(candidatePrefix);
        resp.setPrefixChanged(!Objects.equals(currentPrefix, candidatePrefix));
        // 设计按 layer_id 计数，部署目标从设计派生归属
        long logicCount = nullToZero(logicModelMapper.selectCount(LogicModelDO::getLayerId, id));
        long physicalCount = 0L; // 物理模型整层删除后恒为 0；字段保留兼容前端
        resp.setLogicCount(logicCount);
        resp.setPhysicalCount(physicalCount);
        resp.setTruncated(false);
        resp.setItems(new ArrayList<>());

        boolean applicable = DwLayerDO.NODE_TYPE_LAYER.equals(layer.getNodeType());
        resp.setApplicable(applicable);
        if (!applicable) {
            resp.setBlocked(false);
            resp.setReason("分类节点不设置表前缀，不适用候选表名预览");
            return resp;
        }
        long referenceCount = logicCount + physicalCount;
        boolean blocked = resp.getPrefixChanged() && referenceCount > 0;
        resp.setBlocked(blocked);
        if (blocked) {
            resp.setReason(String.format("分层已被 %d 个模型引用（逻辑模型 %d、物理模型 %d），"
                            + "当前策略不允许改变其表前缀，以下候选表名仅供评估影响",
                    referenceCount, logicCount, physicalCount));
        }

        // 只预览有表名的物理模型；多取一条用于判断是否截断
        // 物理模型删除后：前缀预览改为基于逻辑模型 code 估算表名
        List<LogicModelDO> models = logicModelMapper.selectListByLayer(id);
        if (models == null) {
            models = List.of();
        }
        boolean truncated = models.size() > PREFIX_PREVIEW_ITEM_LIMIT;
        resp.setTruncated(truncated);
        if (truncated) {
            models = models.subList(0, PREFIX_PREVIEW_ITEM_LIMIT);
        }
        DwLayerDO candidateLayer = new DwLayerDO();
        candidateLayer.setTablePrefix(candidatePrefix);
        List<DwLayerPrefixPreviewRespVO.Item> items = new ArrayList<>(models.size());
        for (LogicModelDO model : models) {
            items.add(buildPrefixPreviewItem(layer, candidateLayer, model));
        }
        resp.setItems(items);
        return resp;
    }

    /**
     * 构造前缀预览项。
     *
     * @param layer 分层
     * @param candidateLayer 候选分层
     * @param model 模型
     * @return 项
     */
    private DwLayerPrefixPreviewRespVO.Item buildPrefixPreviewItem(DwLayerDO layer, DwLayerDO candidateLayer,
            LogicModelDO model) {
        DwLayerPrefixPreviewRespVO.Item item = new DwLayerPrefixPreviewRespVO.Item();
        item.setModelId(model.getId());
        item.setModelName(model.getName());
        item.setCode(model.getCode());
        item.setStatus(model.getStatus());
        item.setDeployStatus(null);
        item.setCreateMode(model.getCreateMode());
        String englishName = model.getCode();
        try {
            item.setCurrentTableName(tableNameResolver.buildFullTableName(layer, englishName));
            item.setCandidateTableName(tableNameResolver.buildFullTableName(candidateLayer, englishName));
        } catch (ServiceException ex) {
            item.setCurrentTableName(null);
            item.setCandidateTableName(null);
            item.setNote(ex.getMessage());
            return item;
        }
        item.setChanged(!Objects.equals(item.getCurrentTableName(), item.getCandidateTableName()));
        return item;
    }


    /**
     * 前缀归一化：空值与空白视为无前缀（空串），其余去首尾空格并转小写
     */
    private String normalizePrefix(String prefix) {
        return StrUtil.isBlank(prefix) ? "" : prefix.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 为空数值提供零值。
     *
     * @param value 待处理的值
     * @return 空值目标零数值
     */
    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

}
