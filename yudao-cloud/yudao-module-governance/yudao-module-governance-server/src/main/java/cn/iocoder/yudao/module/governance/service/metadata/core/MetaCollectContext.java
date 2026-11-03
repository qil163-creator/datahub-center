package cn.iocoder.yudao.module.governance.service.metadata.core;

import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaChangeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import lombok.Data;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次采集执行的上下文：任务/实例引用、来源信息与差异计数器
 *
 * @author Li
 */
@Data
public class MetaCollectContext {

    /** 在每次元数据写事务内调用，以隔离已过期工作线程；导入操作没有任务租约。 */
    private Runnable writeFence = () -> {
    };

    /** 采集实例编号 */
    private Long instanceId;

    /** 采集任务编号（导入/手工登记可空） */
    private Long taskId;

    /** dataforge 数据源编号 */
    private Long datasourceId;

    /** 来源系统编号 */
    private Long sourceSystemId;

    /** 来源系统名称 */
    private String sourceSystemName;

    /** 数据库类型 */
    private String dbType;

    /** 触发方式，见 {@link MetaCollectInstanceDO} TRIGGER_* */
    private Integer triggerType;

    /** 结构化日志器 */
    private MetaCollectLogger logger;

    /** 差异计数器 */
    private final AtomicInteger tableAdd = new AtomicInteger();

    private final AtomicInteger tableUpdate = new AtomicInteger();

    private final AtomicInteger tableDelete = new AtomicInteger();

    private final AtomicInteger columnAdd = new AtomicInteger();

    private final AtomicInteger columnUpdate = new AtomicInteger();

    private final AtomicInteger columnDelete = new AtomicInteger();

    private final AtomicInteger tableProcessed = new AtomicInteger();

    private final AtomicInteger columnProcessed = new AtomicInteger();

    /**
     * 触发方式映射到变更来源：调度/手动/事件 → 采集；导入 → 导入；手工登记 → 手工
     */
    public int resolveChangeSource() {
        if (triggerType == null) {
            return MetaChangeDO.SOURCE_COLLECT;
        }
        switch (triggerType) {
            case MetaCollectInstanceDO.TRIGGER_IMPORT:
                return MetaChangeDO.SOURCE_IMPORT;
            case MetaCollectInstanceDO.TRIGGER_REGISTER:
                return MetaChangeDO.SOURCE_MANUAL;
            default:
                return MetaChangeDO.SOURCE_COLLECT;
        }
    }

    /**
     * 对象来源映射：导入 → 导入；手工登记 → 手工；其余 → 采集
     */
    public int resolveOrigin() {
        if (triggerType == null) {
            return MetaDbDO.ORIGIN_COLLECT;
        }
        switch (triggerType) {
            case MetaCollectInstanceDO.TRIGGER_IMPORT:
                return MetaDbDO.ORIGIN_IMPORT;
            case MetaCollectInstanceDO.TRIGGER_REGISTER:
                return MetaDbDO.ORIGIN_MANUAL;
            default:
                return MetaDbDO.ORIGIN_COLLECT;
        }
    }

}
