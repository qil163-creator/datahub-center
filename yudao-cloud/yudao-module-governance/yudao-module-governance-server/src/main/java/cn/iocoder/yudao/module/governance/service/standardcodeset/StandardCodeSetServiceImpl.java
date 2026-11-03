package cn.iocoder.yudao.module.governance.service.standardcodeset;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetImportExcelVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_IMPORT_FIELD_MISSING;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_IMPORT_ITEM_DUPLICATE;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_IMPORT_LIST_EMPTY;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_IMPORT_NAME_MISSING;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_REFERENCED;

/**
 * 数据标准--标准码 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class StandardCodeSetServiceImpl implements StandardCodeSetService {

    @Resource
    private StandardCatalogService catalogService;

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeMappingMapper valueMappings;

    @Resource
    private StandardCodeSetMapper standardCodeSetMapper;

    @Resource
    private StandardCodeItemMapper standardCodeItemMapper;

    @Resource
    private StandardElementMapper standardElementMapper;

    @Resource
    private StandardDocMapper standardDocMapper;

    @Resource
    private CategoryDatametaMapper categoryMapper;

    /**
     * 创建数据标准--标准码
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer createStandardCodeSet(StandardCodeSetSaveReqVO createReqVO) {
        createReqVO.setContactNumber(catalogService.ownerContact(createReqVO.getPersonCharge()));
        catalogService.validate(createReqVO.getCategoryId(), createReqVO.getDocumentId(), createReqVO.getStandardType(),
                createReqVO.getDataLength(), createReqVO.getDataScale());
        lockReferences(createReqVO.getCategoryId(), createReqVO.getDocumentId(), createReqVO.getStandardType());
        createReqVO.setRevision(0L);
        // 插入
        StandardCodeSetDO standardCodeSet = BeanUtils.toBean(createReqVO, StandardCodeSetDO.class);
        standardCodeSetMapper.insert(standardCodeSet);

        // 返回
        return standardCodeSet.getId();
    }

    /**
     * 更新数据标准--标准码
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStandardCodeSet(StandardCodeSetSaveReqVO updateReqVO) {
        updateReqVO.setContactNumber(catalogService.ownerContact(updateReqVO.getPersonCharge()));
        catalogService.validate(updateReqVO.getCategoryId(), updateReqVO.getDocumentId(), updateReqVO.getStandardType(),
                updateReqVO.getDataLength(), updateReqVO.getDataScale());
        lockReferences(updateReqVO.getCategoryId(), updateReqVO.getDocumentId(), updateReqVO.getStandardType());
        StandardCodeSetDO dbCodeSet = lockStandardCodeSet(updateReqVO.getId());
        StandardCatalogService.checkRevision(updateReqVO.getRevision(), dbCodeSet.getRevision());
        updateReqVO.setRevision((dbCodeSet.getRevision() == null ? 0L : dbCodeSet.getRevision()) + 1);
        // 更新。字典类型是码值的关联键，创建后不允许修改，避免码值脱钩
        StandardCodeSetDO updateObj = BeanUtils.toBean(updateReqVO, StandardCodeSetDO.class);
        updateObj.setType(dbCodeSet.getType());
        standardCodeSetMapper.updateById(updateObj);
    }

    /**
     * 锁定引用。
     */
    private void lockReferences(Long categoryId, Long documentId, String standardType) {
        // 与共享引用触发器保持类目、标准文件的相同加锁顺序。
        if (categoryId != null && categoryMapper.selectByIdForUpdate(categoryId) == null) {
            throw exception(
                    cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_ELEMENT_CATEGORY_NOT_EXISTS);
        }
        if (documentId == null) {
            return;
        }
        var document = standardDocMapper.selectByIdForUpdate(documentId);
        if (document == null || !java.util.Objects.equals(document.getStdType(), standardType)) {
            throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CATALOG_INVALID,
                    "标准文件不存在或不属于所选标准类型");
        }
    }

    /**
     * 删除数据标准--标准码
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteStandardCodeSet(Integer id) {
        StandardCodeSetDO codeSet = lockStandardCodeSet(id);
        // 校验未被标准数据元引用
        validateNotReferencedByElement(List.of(id));
        valueMappings.delete(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeMappingDO>().eq(
                        cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeMappingDO::getCodeSetId,
                        id));
        standardCodeItemMapper.deleteByDictTypes(List.of(codeSet.getType()));
        standardCodeSetMapper.deleteById(id);
    }

    /**
     * 批量删除数据标准--标准码
     *
     *  @param ids 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteStandardCodeSetListByIds(List<Integer> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        List<Integer> lockedIds = ids.stream().distinct().sorted().toList();
        List<StandardCodeSetDO> codeSets = lockedIds.stream().map(this::lockStandardCodeSet).toList();
        // 持有码表行锁后再校验数据元引用。
        validateNotReferencedByElement(lockedIds);
        valueMappings.delete(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeMappingDO>().in(
                        cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeMappingDO::getCodeSetId,
                        lockedIds));
        List<String> dictTypes = convertList(codeSets, StandardCodeSetDO::getType);
        if (CollUtil.isNotEmpty(dictTypes)) {
            standardCodeItemMapper.deleteByDictTypes(dictTypes);
        }
        standardCodeSetMapper.deleteByIds(lockedIds);
    }

    /**
     * 校验标准代码集的非被引用按数据元，不满足约束时拒绝当前操作。
     */
    private void validateNotReferencedByElement(List<Integer> codeSetIds) {
        Long referenceCount = standardElementMapper.selectCountByCodeSetIds(codeSetIds);
        if (referenceCount != null && referenceCount > 0) {
            throw exception(STANDARD_CODE_SET_REFERENCED);
        }
    }

    /**
     * 校验标准代码集记录存在，不存在时抛出业务异常。
     */
    private StandardCodeSetDO validateStandardCodeSetExists(Integer id) {
        StandardCodeSetDO codeSet = standardCodeSetMapper.selectById(id);
        if (codeSet == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
        return codeSet;
    }

    /**
     * 锁定标准代码集。
     */
    private StandardCodeSetDO lockStandardCodeSet(Integer id) {
        StandardCodeSetDO codeSet = id == null ? null : standardCodeSetMapper.selectByIdForUpdate(
                id);
        if (codeSet == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
        return codeSet;
    }

    /**
     * 切换标准启用状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Integer id, Short status, Long revision) {
        if (status == null || status < 0 || status > 1) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CATALOG_INVALID,
                    "状态仅支持启用或停用");
        }
        var row = lockStandardCodeSet(id);
        StandardCatalogService.checkRevision(revision, row.getRevision());
        standardCodeSetMapper.updateById(StandardCodeSetDO.builder().id(id).status(status)
                .revision((row.getRevision() == null ? 0L : row.getRevision()) + 1).build());
    }

    /**
     * 获得数据标准--标准码
     *
     *  @param id 编号
     *  @return 数据标准--标准码
     */
    @Override
    public StandardCodeSetDO getStandardCodeSet(Integer id) {
        return standardCodeSetMapper.selectById(id);
    }

    /**
     * 获得数据标准--标准码分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据标准--标准码分页
     */
    @Override
    public PageResult<StandardCodeSetDO> getStandardCodeSetPage(StandardCodeSetPageReqVO pageReqVO) {
        if (pageReqVO.getCategoryId() != null) {
            pageReqVO.setCategoryIds(catalogService.descendants(pageReqVO.getCategoryId()));
        }
        return standardCodeSetMapper.selectPage(pageReqVO);
    }

    /**
     * 填充码表下的码值条目数量
     *
     *  @param list 码表列表
     */
    @Override
    public void fillItemCounts(List<StandardCodeSetRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        List<String> dictTypes = convertList(list, StandardCodeSetRespVO::getType);
        Map<String, Integer> countMap = standardCodeItemMapper.selectCountMapByDictTypes(dictTypes);
        list.forEach(item -> item.setItemCount(countMap.getOrDefault(item.getType(), 0)));
    }

    /**
     * 批量导入码表与码值
     *
     *  @param importList    导入行列表（每行一个码值，按字典类型归组）
     *  @param updateSupport 是否支持更新（按字典类型 + 键值匹配已有码值）
     *  @return 导入结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public StandardCodeSetImportRespVO importStandardCodeSetList(List<StandardCodeSetImportExcelVO> importList,
            boolean updateSupport) {
        if (CollUtil.isEmpty(importList)) {
            throw exception(STANDARD_CODE_SET_IMPORT_LIST_EMPTY);
        }
        StandardCodeSetImportRespVO respVO = StandardCodeSetImportRespVO.builder()
                .createCodeSetTypes(new ArrayList<>())
                .createItemKeys(new ArrayList<>())
                .updateItemKeys(new ArrayList<>())
                .failureReasons(new LinkedHashMap<>())
                .build();
        // 导入过程中新建的码表缓存，避免同一文件内重复创建
        Map<String, StandardCodeSetDO> codeSetCache = new LinkedHashMap<>();
        for (int i = 0; i < importList.size(); i++) {
            StandardCodeSetImportExcelVO importVO = importList.get(i);
            String rowKey = StrUtil.isNotBlank(importVO.getType()) && StrUtil.isNotBlank(importVO.getValue())
                    ? importVO.getType() + "=" + importVO.getValue() : "第 " + (i + 2) + " 行";
            try {
                importCodeItemRow(importVO, updateSupport, respVO, codeSetCache);
            } catch (ServiceException ex) {
                respVO.getFailureReasons().put(rowKey, ex.getMessage());
            }
        }
        return respVO;
    }

    /**
     * 导入编码明细数据行。
     */
    private void importCodeItemRow(StandardCodeSetImportExcelVO importVO, boolean updateSupport,
            StandardCodeSetImportRespVO respVO, Map<String, StandardCodeSetDO> codeSetCache) {
        // 必填校验：字典类型、码值键值、码值标签
        if (StrUtil.hasBlank(importVO.getType(), importVO.getValue(), importVO.getLabel())) {
            throw exception(STANDARD_CODE_SET_IMPORT_FIELD_MISSING);
        }
        String type = importVO.getType().trim();
        String value = importVO.getValue().trim();
        // 解析或创建码表
        StandardCodeSetDO codeSet = codeSetCache.get(type);
        if (codeSet == null) {
            codeSet = standardCodeSetMapper.selectByType(type);
            if (codeSet != null) {
                // 与码表删除共用父行锁；锁后再写码值，避免 deleteByDictTypes 后并发遗留孤儿码值。
                codeSet = lockStandardCodeSet(codeSet.getId());
            }
        }
        if (codeSet == null) {
            if (StrUtil.isBlank(importVO.getCodeSetName())) {
                throw exception(STANDARD_CODE_SET_IMPORT_NAME_MISSING, type);
            }
            codeSet = StandardCodeSetDO.builder()
                    .name(importVO.getCodeSetName().trim())
                    .type(type)
                    .status((short) 0)
                    .build();
            standardCodeSetMapper.insert(codeSet);
            respVO.getCreateCodeSetTypes().add(type);
        }
        codeSetCache.put(type, codeSet);
        // 创建或更新码值
        StandardCodeItemDO existing = standardCodeItemMapper.selectByDictTypeAndValue(type, value);
        if (existing == null) {
            standardCodeItemMapper.insert(StandardCodeItemDO.builder()
                    .codeSetId(codeSet.getId())
                    .dictType(type)
                    .levelNo(0)
                    .value(value)
                    .label(importVO.getLabel().trim())
                    .sort(importVO.getSort() != null ? importVO.getSort() : 0)
                    .status((short) 0)
                    .remark(importVO.getRemark())
                    .build());
            respVO.getCreateItemKeys().add(type + "=" + value);
            return;
        }
        if (!updateSupport) {
            throw exception(STANDARD_CODE_SET_IMPORT_ITEM_DUPLICATE);
        }
        StandardCodeItemDO updateObj = StandardCodeItemDO.builder()
                .id(existing.getId())
                .codeSetId(codeSet.getId())
                .label(importVO.getLabel().trim())
                .remark(importVO.getRemark())
                .build();
        if (importVO.getSort() != null) {
            updateObj.setSort(importVO.getSort());
        }
        standardCodeItemMapper.updateById(updateObj);
        respVO.getUpdateItemKeys().add(type + "=" + value);
    }

}
