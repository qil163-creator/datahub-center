package cn.iocoder.yudao.framework.etl.core.runtime;

import java.util.Map;

/** 受控运行观测，不得修改观察到的数据。 */
@FunctionalInterface
public interface EtlRowObserver {

    /**
     * 接收当前数据行并更新处理进度或输出。
     */
    void onRow(String nodeNo, String port, Map<String, Object> row);

    /** 质量结果在对应行之前报告；消息可能含业务数据，调用方必须自行控制暴露字段。 */
    default void onQualityResult(String nodeNo,
            java.util.List<cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort.Violation> violations) {
    }
}
