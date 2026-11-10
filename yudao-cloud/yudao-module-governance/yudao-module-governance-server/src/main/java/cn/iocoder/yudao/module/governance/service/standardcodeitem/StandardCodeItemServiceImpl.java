package cn.iocoder.yudao.module.governance.service.standardcodeitem;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.hutool.core.util.StrUtil.isBlank;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据标准--码值 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class StandardCodeItemServiceImpl implements StandardCodeItemService {

    @Resource
    private cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeMappingMapper valueMappings;

    @Resource
    private StandardCodeItemMapper standardCodeItemMapper;

    @Resource
    private StandardCodeSetMapper standardCodeSetMapper;

    /**
     * 使用给定初始状态初始化标准代码项。
     */
    public StandardCodeItemServiceImpl() {
    }

    /**
     * 使用给定标准编码明细数据库映射、标准编码集合数据库映射初始化标准代码项。
     */
    public StandardCodeItemServiceImpl(StandardCodeItemMapper standardCodeItemMapper,
            StandardCodeSetMapper standardCodeSetMapper) {
        this.standardCodeItemMapper = standardCodeItemMapper;
        this.standardCodeSetMapper = standardCodeSetMapper;
    }

    /**
     * 创建码值
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer createStandardCodeItem(StandardCodeItemSaveReqVO createReqVO) {
        StandardCodeSetDO codeSet = resolveAndLockCodeSet(createReqVO.getCodeSetId(), createReqVO.getDictType());
        validateValueUnique(null, codeSet.getId(), createReqVO.getValue());
        StandardCodeItemDO item = BeanUtils.toBean(createReqVO, StandardCodeItemDO.class);
        item.setCodeSetId(codeSet.getId());
        item.setDictType(codeSet.getType());
        standardCodeItemMapper.insert(item);
        return item.getId();
    }

    /**
     * 更新码值
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStandardCodeItem(StandardCodeItemSaveReqVO updateReqVO) {
        StandardCodeItemDO snapshot = validateStandardCodeItemExists(updateReqVO.getId());
        StandardCodeSetDO codeSet = resolveCodeSet(updateReqVO.getCodeSetId(), updateReqVO.getDictType());
        Integer dbCodeSetId = snapshot.getCodeSetId();
        if (dbCodeSetId == null) {
            StandardCodeSetDO legacyCodeSet = resolveCodeSet(null, snapshot.getDictType());
            dbCodeSetId = legacyCodeSet.getId();
        }
        if (!Objects.equals(dbCodeSetId, codeSet.getId())) {
            throw exception(STANDARD_CODE_ITEM_CODE_SET_CONFLICT);
        }
        codeSet = lockCodeSet(codeSet.getId());
        validateCodeSetIdentity(codeSet, updateReqVO.getCodeSetId(), updateReqVO.getDictType());
        lockItemInCodeSet(updateReqVO.getId(), codeSet);
        validateValueUnique(updateReqVO.getId(), codeSet.getId(), updateReqVO.getValue());
        StandardCodeItemDO updateObj = BeanUtils.toBean(updateReqVO, StandardCodeItemDO.class);
        updateObj.setCodeSetId(codeSet.getId());
        updateObj.setDictType(codeSet.getType());
        standardCodeItemMapper.updateById(updateObj);
    }

    /**
     * 删除码值
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteStandardCodeItem(Integer id) {
        StandardCodeItemDO snapshot = validateStandardCodeItemExists(id);
        StandardCodeSetDO codeSet = lockOwningCodeSet(snapshot);
        lockItemInCodeSet(id, codeSet);
        validateNotReferenced(id);
        standardCodeItemMapper.deleteById(id);
    }

    /**
     * 批量删除码值
     *
     *  @param ids 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteStandardCodeItemListByIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Integer> sortedIds = ids.stream().distinct().sorted().toList();
        List<StandardCodeItemDO> snapshots = new ArrayList<>(sortedIds.size());
        for (Integer id : sortedIds) {
            snapshots.add(validateStandardCodeItemExists(id));
        }
        Map<Integer, StandardCodeSetDO> codeSets = snapshots.stream()
                .map(this::resolveOwningCodeSet)
                .collect(Collectors.toMap(StandardCodeSetDO::getId, Function.identity(), (left, right) -> left));
        for (Integer codeSetId : codeSets.keySet().stream().sorted().toList()) {
            codeSets.put(codeSetId, lockCodeSet(codeSetId));
        }
        for (StandardCodeItemDO snapshot : snapshots.stream()
                .sorted(Comparator.comparing(StandardCodeItemDO::getId)).toList()) {
            StandardCodeSetDO codeSet = codeSets.get(resolveOwningCodeSet(snapshot).getId());
            lockItemInCodeSet(snapshot.getId(), codeSet);
        }
        for (Integer id : sortedIds) {
            validateNotReferenced(id);
        }
        standardCodeItemMapper.deleteByIds(sortedIds);
    }

    /**
     * 获得码值
     *
     *  @param id 编号
     *  @return 码值
     */
    @Override
    public StandardCodeItemDO getStandardCodeItem(Integer id) {
        return standardCodeItemMapper.selectById(id);
    }

    /**
     * 获得码值分页
     *
     *  @param pageReqVO 分页查询
     *  @return 码值分页
     */
    @Override
    public PageResult<StandardCodeItemDO> getStandardCodeItemPage(StandardCodeItemPageReqVO pageReqVO) {
        return standardCodeItemMapper.selectPage(pageReqVO);
    }

    /**
     * 校验标准代码项记录存在，不存在时抛出业务异常。
     */
    private StandardCodeItemDO validateStandardCodeItemExists(Integer id) {
        if (id == null) {
            throw exception(STANDARD_CODE_ITEM_NOT_EXISTS);
        }
        StandardCodeItemDO item = standardCodeItemMapper.selectById(id);
        if (item == null) {
            throw exception(STANDARD_CODE_ITEM_NOT_EXISTS);
        }
        return item;
    }

    /**
     * 解析标准代码项的编码集合。
     */
    private StandardCodeSetDO resolveCodeSet(Integer codeSetId, String dictType) {
        if (codeSetId == null && isBlank(dictType)) {
            throw exception(STANDARD_CODE_ITEM_CODE_SET_REQUIRED);
        }
        StandardCodeSetDO byId = codeSetId == null ? null : standardCodeSetMapper.selectById(codeSetId);
        if (codeSetId != null && byId == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
        StandardCodeSetDO byType = isBlank(dictType) ? null : standardCodeSetMapper.selectByType(dictType.trim());
        if (!isBlank(dictType) && byType == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
        if (byId != null && byType != null && !Objects.equals(byId.getId(), byType.getId())) {
            throw exception(STANDARD_CODE_ITEM_CODE_SET_CONFLICT);
        }
        return byId != null ? byId : byType;
    }

    /**
     * 解析标准代码项的与锁定编码集合。
     */
    private StandardCodeSetDO resolveAndLockCodeSet(Integer codeSetId, String dictType) {
        StandardCodeSetDO resolved = resolveCodeSet(codeSetId, dictType);
        StandardCodeSetDO locked = lockCodeSet(resolved.getId());
        validateCodeSetIdentity(locked, codeSetId, dictType);
        return locked;
    }

    /**
     * 锁定编码集合。
     */
    private StandardCodeSetDO lockCodeSet(Integer codeSetId) {
        StandardCodeSetDO codeSet = standardCodeSetMapper.selectByIdForUpdate(
                codeSetId);
        if (codeSet == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
        return codeSet;
    }

    /**
     * 解析标准代码项的所属编码集合。
     */
    private StandardCodeSetDO resolveOwningCodeSet(StandardCodeItemDO item) {
        if (item.getCodeSetId() != null) {
            StandardCodeSetDO codeSet = standardCodeSetMapper.selectById(item.getCodeSetId());
            if (codeSet == null) {
                throw exception(STANDARD_CODE_SET_NOT_EXISTS);
            }
            return codeSet;
        }
        return resolveCodeSet(null, item.getDictType());
    }

    /**
     * 锁定所属编码集合。
     */
    private StandardCodeSetDO lockOwningCodeSet(StandardCodeItemDO item) {
        StandardCodeSetDO codeSet = lockCodeSet(resolveOwningCodeSet(item).getId());
        if (item.getCodeSetId() == null && !Objects.equals(item.getDictType(), codeSet.getType())) {
            throw exception(STANDARD_CODE_ITEM_CODE_SET_CONFLICT);
        }
        return codeSet;
    }

    /**
     * 锁定明细范围内编码集合。
     */
    private StandardCodeItemDO lockItemInCodeSet(Integer itemId, StandardCodeSetDO codeSet) {
        StandardCodeItemDO item = standardCodeItemMapper.selectByIdForUpdate(
                itemId);
        if (item == null) {
            throw exception(STANDARD_CODE_ITEM_NOT_EXISTS);
        }
        boolean belongs = Objects.equals(item.getCodeSetId(), codeSet.getId())
                || item.getCodeSetId() == null && Objects.equals(item.getDictType(), codeSet.getType());
        if (!belongs) {
            throw exception(STANDARD_CODE_ITEM_CODE_SET_CONFLICT);
        }
        return item;
    }

    /**
     * 校验标准代码项的编码集合身份，不满足约束时拒绝当前操作。
     */
    private void validateCodeSetIdentity(StandardCodeSetDO codeSet, Integer codeSetId, String dictType) {
        if (codeSetId != null && !Objects.equals(codeSet.getId(), codeSetId)
                || !isBlank(dictType) && !Objects.equals(codeSet.getType(), dictType.trim())) {
            throw exception(STANDARD_CODE_ITEM_CODE_SET_CONFLICT);
        }
    }

    /**
     * 校验标准代码项的值在指定范围内唯一。
     */
    private void validateValueUnique(Integer id, Integer codeSetId, String value) {
        StandardCodeItemDO item = standardCodeItemMapper.selectByCodeSetIdAndValue(codeSetId, value);
        if (item == null) {
            return;
        }
        if (id == null || !Objects.equals(item.getId(), id)) {
            throw exception(STANDARD_CODE_ITEM_VALUE_DUPLICATE);
        }
    }

    /**
     * 校验标准代码项未被映射引用。
     */
    private void validateNotReferenced(Integer id) {
        if (valueMappings.selectCount(
                cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeMappingDO::getCodeItemId,
                id) > 0) {
            throw exception(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_ITEM_REFERENCED);
        }
    }


}
