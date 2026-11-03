package cn.iocoder.yudao.module.governance.service.standardcodeset;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeMappingDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeMappingMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 同一码表内的原始值映射；与码值写入共用码表行锁。 */
@Service
@RequiredArgsConstructor
public class StandardCodeMappingService {

    private final StandardCodeMappingMapper mappings;

    private final StandardCodeSetMapper sets;

    private final StandardCodeItemMapper items;

    private final StandardCatalogService catalog;

    /**
     * 创建关联配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(StandardCodeMappingSaveReqVO request) {
        lock(request.getCodeSetId());
        validate(request, null);
        var row = BeanUtils.toBean(request, StandardCodeMappingDO.class);
        row.setId(null);
        row.setRevision(0L);
        mappings.insert(row);
        return row.getId();
    }

    /**
     * 更新关联配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(StandardCodeMappingSaveReqVO request) {
        lock(request.getCodeSetId());
        var prior = require(request.getCodeSetId(), request.getId());
        if (request.getRevision() == null) {
            throw exception(STANDARD_CATALOG_CONFLICT);
        }
        StandardCatalogService.checkRevision(request.getRevision(), prior.getRevision());
        validate(request, prior.getId());
        var row = BeanUtils.toBean(request, StandardCodeMappingDO.class);
        row.setRevision(prior.getRevision() + 1);
        mappings.updateById(row);
    }

    /**
     * 删除关联配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Integer codeSetId, Long id) {
        lock(codeSetId);
        require(codeSetId, id);
        mappings.deleteById(id);
    }

    /**
     * 查询关联配置详情。
     */
    public StandardCodeMappingRespVO get(Integer codeSetId, Long id) {
        return convert(List.of(require(codeSetId, id))).getFirst();
    }

    /**
     * 查询关联配置分页。
     */
    public PageResult<StandardCodeMappingRespVO> page(StandardCodeMappingPageReqVO request) {
        if (sets.selectById(request.getCodeSetId()) == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
        var query = new LambdaQueryWrapperX<StandardCodeMappingDO>()
                .eq(StandardCodeMappingDO::getCodeSetId, request.getCodeSetId())
                .likeIfPresent(StandardCodeMappingDO::getOriginalValue, request.getOriginalValue());
        if ("id".equals(request.getSortField())) {
            query.orderBy(true, "asc".equals(request.getSortOrder()), StandardCodeMappingDO::getId);
        } else {
            query.orderBy(true, "asc".equals(request.getSortOrder()), StandardCodeMappingDO::getCreateTime)
                    .orderByDesc(StandardCodeMappingDO::getId);
        }
        var result = mappings.selectPage(request, query);
        return new PageResult<>(convert(result.getList()), result.getTotal());
    }

    /**
     * 读取并锁定标准代码映射记录，供事务内更新使用。
     */
    private void lock(Integer id) {
        if (id == null || sets.selectByIdForUpdate(id) == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
    }

    /**
     * 读取并校验必要的标准代码映射上下文或记录。
     */
    private StandardCodeMappingDO require(Integer codeSetId, Long id) {
        var row = id == null ? null : mappings.selectById(id);
        if (row == null || !Objects.equals(row.getCodeSetId(), codeSetId)) {
            throw exception(STANDARD_CODE_MAPPING_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 校验标准代码映射配置满足当前业务约束。
     */
    private void validate(StandardCodeMappingSaveReqVO request, Long id) {
        var item = request.getCodeItemId() == null ? null : items.selectById(request.getCodeItemId());
        if (item == null || !Objects.equals(item.getCodeSetId(), request.getCodeSetId()) || !Objects.equals(
                item.getStatus(), (short) 0)) {
            throw exception(STANDARD_CATALOG_INVALID, "请选择当前码表的有效码值");
        }
        var existing = mappings.selectOne(new LambdaQueryWrapperX<StandardCodeMappingDO>()
                .eq(StandardCodeMappingDO::getCodeSetId, request.getCodeSetId())
                .eq(StandardCodeMappingDO::getOriginalValue, request.getOriginalValue()));
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(STANDARD_CODE_MAPPING_DUPLICATE);
        }
    }

    /**
     * 将标准代码映射记录转换为接口响应。
     */
    private List<StandardCodeMappingRespVO> convert(List<StandardCodeMappingDO> rows) {
        var people = catalog.userMap(rows.stream().map(StandardCodeMappingDO::getCreator).toList(), List.of());
        return rows.stream().map(row -> {
            var result = BeanUtils.toBean(row, StandardCodeMappingRespVO.class);
            var item = items.selectById(row.getCodeItemId());
            if (item != null) {
                result.setCodeName(item.getLabel());
                result.setCodeValue(item.getValue());
            }
            result.setCreatorName(StandardCatalogService.name(people, row.getCreator()));
            return result;
        }).toList();
    }
}
