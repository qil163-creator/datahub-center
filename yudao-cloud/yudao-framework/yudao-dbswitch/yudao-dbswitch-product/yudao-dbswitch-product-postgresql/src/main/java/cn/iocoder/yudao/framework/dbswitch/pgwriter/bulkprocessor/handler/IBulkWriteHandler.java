package cn.iocoder.yudao.framework.dbswitch.pgwriter.bulkprocessor.handler;

import java.util.List;

/**
 * 批量写入回调接口,定义一批实体的落库写入操作,由批量缓冲处理器在攒批完成后调用。
 */
public interface IBulkWriteHandler<TEntity> {

    /**
     * 将当前缓冲器交付的一批实体写入目标。
     */
    void write(List<TEntity> entities) throws Exception;

}
