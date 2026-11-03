package cn.iocoder.yudao.module.governance.service.metadata.core;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectLogDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectLogMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 采集实例结构化日志器：内存缓冲 + 分批落库
 *
 * 日志格式对齐 qData：[阶段] [对象引用] 内容，毫秒时间戳
 *
 * @author Li
 */
public class MetaCollectLogger {

    private static final int FLUSH_SIZE = 50;

    private static final int CONTENT_MAX_LENGTH = 2000;

    private final MetaCollectLogMapper logMapper;

    private final Long instanceId;

    private final Long taskId;

    private final List<MetaCollectLogDO> buffer = new ArrayList<>(FLUSH_SIZE);

    /**
     * 使用给定日志数据库映射、实例编号、任务编号初始化元数据采集日志记录器。
     */
    public MetaCollectLogger(MetaCollectLogMapper logMapper, Long instanceId, Long taskId) {
        this.logMapper = logMapper;
        this.instanceId = instanceId;
        this.taskId = taskId;
    }

    /**
     * 记录元数据采集日志记录器运行信息。
     */
    public void info(String stage, String objectRef, String content) {
        append(MetaCollectLogDO.LEVEL_INFO, stage, objectRef, content);
    }

    /**
     * 记录元数据采集日志记录器运行告警。
     */
    public void warn(String stage, String objectRef, String content) {
        append(MetaCollectLogDO.LEVEL_WARN, stage, objectRef, content);
    }

    /**
     * 记录元数据采集日志记录器运行错误。
     */
    public void error(String stage, String objectRef, String content) {
        append(MetaCollectLogDO.LEVEL_ERROR, stage, objectRef, content);
    }

    /**
     * 将带级别和时间的消息追加到采集实例日志。
     */
    private synchronized void append(String level, String stage, String objectRef, String content) {
        buffer.add(MetaCollectLogDO.builder()
                .instanceId(instanceId)
                .taskId(taskId)
                .logTime(LocalDateTime.now())
                .logLevel(level)
                .stage(stage)
                .objectRef(objectRef)
                .content(StrUtil.maxLength(content, CONTENT_MAX_LENGTH))
                .build());
        if (buffer.size() >= FLUSH_SIZE) {
            flush();
        }
    }

    /**
     * 缓冲落库（执行结束或缓冲满时调用）
     */
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        logMapper.insertBatch(new ArrayList<>(buffer));
        buffer.clear();
    }

}
