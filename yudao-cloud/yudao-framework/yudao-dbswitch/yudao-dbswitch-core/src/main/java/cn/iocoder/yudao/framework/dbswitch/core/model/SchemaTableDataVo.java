package cn.iocoder.yudao.framework.dbswitch.core.model;


import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 表数据分页展示对象,封装列名与类型映射、行数据及分页信息,用于表数据预览查询的返回结果。
 */
@Data
@Builder
public class SchemaTableDataVo {

    private Map<String, String> columns;

    private List<Map<String, Object>> rows;

    private Integer page;

    private Integer limit;

    private Integer total;
}
