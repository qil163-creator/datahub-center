package cn.iocoder.yudao.framework.dbswitch.core.model;

import lombok.Data;

import java.util.List;

/**
 * 分页查询请求参数,封装原始 SQL、绑定参数与页码、每页条数,供生成分页 SQL 使用。
 *
 * @ClassName PageSqlRequest
 * @Author zrx
 * @Date 2024/6/14 16:04
 */
@Data
public class PageSqlRequest {

    private Integer page;

    private Integer limit;

    private String sql;

    private List<Object> params;
}
