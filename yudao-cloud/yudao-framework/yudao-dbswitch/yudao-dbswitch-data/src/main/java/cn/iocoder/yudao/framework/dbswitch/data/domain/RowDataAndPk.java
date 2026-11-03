package cn.iocoder.yudao.framework.dbswitch.data.domain;

import lombok.Data;

import java.util.Map;

/**
 * 行数据与主键值的封装对象,增量同步比对时携带整行数据及其主键取值。
 *
 * @ClassName RowDataAndPk
 * @Author zrx
 * @Date 2024/3/4 16:31
 */
@Data
public class RowDataAndPk {

    private Object[] row;

    private Map<String, Object> pkVal;
}
