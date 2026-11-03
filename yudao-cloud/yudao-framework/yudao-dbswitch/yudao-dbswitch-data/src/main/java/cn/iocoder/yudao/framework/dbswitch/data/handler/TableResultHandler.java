package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;

/**
 * 单表迁移结果回调接口,迁移任务完成一张表后回调处理该表的迁移结果(如记录日志、更新进度)。
 *
 * @ClassName TableResultHandler
 * @Author zrx
 * @Date 2022/11/24 20:47
 */
public interface TableResultHandler {

    /**
     * 解析或创建当前值类型对应的处理器。
     */
    void handler(DbSwitchTableResult tableResult);
}
