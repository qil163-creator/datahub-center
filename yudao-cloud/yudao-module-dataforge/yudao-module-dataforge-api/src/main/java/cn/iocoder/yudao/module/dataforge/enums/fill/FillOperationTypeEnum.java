package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 在线填报-草稿操作类型枚举（协同协议与操作日志的最小单元）
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillOperationTypeEnum {

    SET_CELL("SET_CELL", "修改单元格"),
    ADD_ROW("ADD_ROW", "新增行"),
    REMOVE_ROW("REMOVE_ROW", "删除行"),
    REORDER_ROW("REORDER_ROW", "调整行顺序"),
    IMPORT_CONFIRM("IMPORT_CONFIRM", "Excel 导入确认写入"),
    PREFILL("PREFILL", "上期数据带入");

    private final String type;

    private final String name;

}
