package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardAssociationColumnRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardMappingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_ELEMENT_NOT_EXISTS;

/** 标准为平台内共享目录；先验证当前用户对应目录查询权限，再读取当前有效落标事实。 */
@Service
@RequiredArgsConstructor
public class StandardAssociationService {

    private final StandardElementMapper elements;

    private final StandardCodeSetMapper codeSets;

    private final StandardMappingMapper mappings;

    private final MetaColumnMapper columns;

    private final MetaTableMapper tables;

    private final SecurityFrameworkService security;

    /**
     * 获得标准当前已确认落标字段。
     */
    public List<StandardAssociationColumnRespDTO> getColumns(String kind, Integer id) {
        if (id == null || id <= 0 || kind == null || !Set.of("element", "codeset").contains(kind)) {
            throw exception(BAD_REQUEST);
        }
        String permission = "element".equals(
                kind) ? "governance:standard-element:query" : "governance:standard-code-set:query";
        if (SecurityFrameworkUtils.getLoginUserId() == null || !security.hasPermission(permission)) {
            throw exception(FORBIDDEN);
        }
        List<Integer> elementIds;
        if ("element".equals(kind)) {
            if (elements.selectById(id) == null) {
                throw exception(STANDARD_ELEMENT_NOT_EXISTS);
            }
            elementIds = List.of(id);
        } else {
            if (codeSets.selectById(id) == null) {
                throw exception(STANDARD_CODE_SET_NOT_EXISTS);
            }
            elementIds = elements.selectList(StandardElementDO::getCodeSetId, id).stream().map(StandardElementDO::getId)
                    .toList();
        }
        if (elementIds.isEmpty()) {
            return List.of();
        }
        var facts = mappings.selectList(new LambdaQueryWrapperX<StandardMappingDO>()
                .in(StandardMappingDO::getElementId, elementIds)
                .eq(StandardMappingDO::getTargetType, StandardMappingDO.TARGET_TYPE_METADATA_COLUMN)
                .eq(StandardMappingDO::getMappingStatus, StandardMappingDO.STATUS_CONFIRMED));
        Set<Long> columnIds = facts.stream().map(StandardMappingDO::getColumnId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (columnIds.isEmpty()) {
            return List.of();
        }
        Map<Long, MetaColumnDO> currentColumns = columns.selectList(new LambdaQueryWrapperX<MetaColumnDO>()
                        .in(MetaColumnDO::getId, columnIds).eq(MetaColumnDO::getObjectStatus, 1)).stream()
                .collect(Collectors.toMap(MetaColumnDO::getId, value -> value));
        Set<Long> tableIds = currentColumns.values().stream().map(MetaColumnDO::getTableId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (tableIds.isEmpty()) {
            return List.of();
        }
        Map<Long, MetaTableDO> currentTables = tables.selectList(new LambdaQueryWrapperX<MetaTableDO>()
                        .in(MetaTableDO::getId, tableIds).eq(MetaTableDO::getObjectStatus, 1)).stream()
                .collect(Collectors.toMap(MetaTableDO::getId, value -> value));
        Map<Long, StandardAssociationColumnRespDTO> result = new TreeMap<>();
        for (var fact : facts) {
            var column = currentColumns.get(fact.getColumnId());
            if (column == null || fact.getElementId() == null
                    || !Objects.equals(column.getStandardElementId(), fact.getElementId().longValue())) {
                continue;
            }
            var table = currentTables.get(column.getTableId());
            if (table == null) {
                continue;
            }
            var item = new StandardAssociationColumnRespDTO();
            item.setTableId(table.getId());
            item.setTableName(table.getTableName());
            item.setColumnId(column.getId());
            item.setColumnName(column.getColumnName());
            result.put(column.getId(), item);
        }
        return List.copyOf(result.values());
    }
}
