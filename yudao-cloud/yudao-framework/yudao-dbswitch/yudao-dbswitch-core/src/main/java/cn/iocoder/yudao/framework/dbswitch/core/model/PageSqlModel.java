package cn.iocoder.yudao.framework.dbswitch.core.model;

import lombok.Data;

/**
 * 分页 SQL 模型,封装改写后的分页查询 SQL 与对应的总数统计 SQL。
 *
 * @ClassName PageSqlModel
 * @Author zrx
 * @Date 2024/6/14 17:08
 */
@Data
public class PageSqlModel {

    private String pageSql;

    private String pageCountSql;

    /** 默认绑定分页占位符；不支持分页参数的产品可使用经过边界检查的数字字面量。 */
    private boolean bindPageParameters = true;
}
