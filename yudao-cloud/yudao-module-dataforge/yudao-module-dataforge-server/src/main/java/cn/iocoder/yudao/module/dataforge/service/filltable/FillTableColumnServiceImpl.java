package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableColumnSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableColumnMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据填报-填报表字段 Service 实现类
 *
 * 已发布的填报表不允许增删改字段（物理表结构不做在线变更，需先取消发布重建）
 *
 * @author Li
 */
@Service
@Validated
public class FillTableColumnServiceImpl implements FillTableColumnService {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** 系统来源列保留名（发布建表自动追加，用户字段不得占用） */
    private static final java.util.Set<String> RESERVED_COLUMN_NAMES = java.util.Set.of(
            "source_org_id", "period_key", "source_submission_id", "source_row_id");

    @Resource
    private FillTableColumnMapper columnMapper;

    @Resource
    private FillTableMapper fillTableMapper;

    /**
     * 创建填报表字段。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createColumn(FillTableColumnSaveReqVO createReqVO) {
        validateTableEditable(createReqVO.getTableId());
        validateColumn(createReqVO);
        FillTableColumnDO column = BeanUtils.toBean(createReqVO, FillTableColumnDO.class);
        fillDefaults(column);
        // 永久字段码：创建时取字段名，此后改名不再变更（公式/操作日志/来源追踪只引用 fieldCode）
        column.setFieldCode(column.getName());
        columnMapper.insert(column);
        return column.getId();
    }

    /**
     * 更新填报表字段。
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateColumn(FillTableColumnSaveReqVO updateReqVO) {
        FillTableColumnDO old = columnMapper.selectById(updateReqVO.getId());
        if (old == null) {
            throw exception(FILL_TABLE_COLUMN_NOT_EXISTS);
        }
        validateTableEditable(old.getTableId());
        updateReqVO.setTableId(old.getTableId());
        validateColumn(updateReqVO);
        FillTableColumnDO update = BeanUtils.toBean(updateReqVO, FillTableColumnDO.class);
        // 字段码永久不可变：改名也保留首次分配的 fieldCode；存量空值兜底取当前字段名
        update.setFieldCode(old.getFieldCode() != null ? old.getFieldCode() : old.getName());
        columnMapper.updateById(update);
    }

    /**
     * 删除填报表字段。
     *
     * @param id 记录编号
     */
    @Override
    public void deleteColumn(Long id) {
        FillTableColumnDO column = columnMapper.selectById(id);
        if (column == null) {
            throw exception(FILL_TABLE_COLUMN_NOT_EXISTS);
        }
        validateTableEditable(column.getTableId());
        columnMapper.deleteById(id);
    }

    /**
     * 获得填报表的字段列表（按排序）。
     *
     * @param tableId 表编号
     * @return 填报表的字段列表（按排序）结果集合
     */
    @Override
    public List<FillTableColumnRespVO> getColumnListByTableId(Long tableId) {
        return BeanUtils.toBean(columnMapper.selectListByTableId(tableId), FillTableColumnRespVO.class);
    }

    /**
     * 校验表可编辑。
     *
     * @param tableId 表编号
     */
    private void validateTableEditable(Long tableId) {
        FillTableDO fillTable = fillTableMapper.selectById(tableId);
        if (fillTable == null) {
            throw exception(FILL_TABLE_NOT_EXISTS);
        }
        if (fillTable.getStatus() != null && fillTable.getStatus() == 1) {
            throw exception(FILL_TABLE_RELEASED_CANNOT_MODIFY);
        }
    }

    /**
     * 校验列。
     *
     * @param reqVO 请求参数
     */
    private void validateColumn(FillTableColumnSaveReqVO reqVO) {
        if (!NAME_PATTERN.matcher(reqVO.getName()).matches()
                || RESERVED_COLUMN_NAMES.contains(reqVO.getName())) {
            throw exception(FILL_TABLE_COLUMN_NAME_INVALID);
        }
        FillTableColumnDO exists = columnMapper.selectByTableIdAndName(reqVO.getTableId(), reqVO.getName());
        if (exists != null && !exists.getId().equals(reqVO.getId())) {
            throw exception(FILL_TABLE_COLUMN_NAME_DUPLICATE);
        }
    }

    /**
     * 补充默认值。
     *
     * @param column 列
     */
    private void fillDefaults(FillTableColumnDO column) {
        if (column.getFillType() == null) {
            column.setFillType(1);
        }
        if (column.getFillHide() == null) {
            column.setFillHide(0);
        }
        if (column.getNullable() == null) {
            column.setNullable(1);
        }
        if (column.getIfQuery() == null) {
            column.setIfQuery(0);
        }
        if (column.getPk() == null) {
            column.setPk(0);
        }
        if (column.getSort() == null) {
            Long count = columnMapper.selectCount(FillTableColumnDO::getTableId, column.getTableId());
            column.setSort(count == null ? 0 : count.intValue());
        }
    }

}
