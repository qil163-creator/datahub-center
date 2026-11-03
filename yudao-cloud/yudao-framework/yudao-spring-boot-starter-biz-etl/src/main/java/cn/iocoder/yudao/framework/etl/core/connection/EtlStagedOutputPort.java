package cn.iocoder.yudao.framework.etl.core.connection;

import java.util.List;
import java.util.Map;

/** 持久化有界输出意图。执行图完成后的发布与提交由调用方负责。 */
@FunctionalInterface
public interface EtlStagedOutputPort {

    /**
     * 准备当前阶段的数据或目标状态。
     */
    void stage(String nodeNo, List<Map<String, Object>> rows);
}
