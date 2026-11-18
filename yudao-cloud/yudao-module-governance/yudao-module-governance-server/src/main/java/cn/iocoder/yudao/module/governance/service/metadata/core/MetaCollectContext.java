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

    /** 在每次元数据写事务内调用，以隔离已过期工作线程。模型回写没有任务租约。 */
    private Runnable writeFence = () -> {
    };

    /** 采集实例编号 */
    private Long instanceId;

    /** 采集任务编号（模型回写可空） */
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
     * 模型回写使用登记来源，采集任务使用采集来源。
     */
    public int resolveChangeSource() {
        return Integer.valueOf(MetaCollectInstanceDO.TRIGGER_REGISTER).equals(triggerType)
                ? MetaChangeDO.SOURCE_MANUAL : MetaChangeDO.SOURCE_COLLECT;
    }

    /**
     * 映射模型回写和采集任务的对象来源。
     */
    public int resolveOrigin() {
        return Integer.valueOf(MetaCollectInstanceDO.TRIGGER_REGISTER).equals(triggerType)
                ? MetaDbDO.ORIGIN_MANUAL : MetaDbDO.ORIGIN_COLLECT;
    }

}
