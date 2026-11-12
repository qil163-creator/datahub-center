package cn.iocoder.yudao.framework.etl.core.schema;

import java.util.List;
import java.util.Map;

/**
 * 字段来源追踪结果：节点编号 → 输入/输出字段及其来源。
 *
 * @param nodes  各节点的追踪结果，按拓扑序
 * @param errors 字段推导失败信息（与 {@link EtlSchemaResult#getErrors()} 一致，不含连接与凭据）
 */
public record EtlFieldOriginResult(Map<String, NodeTrace> nodes, List<String> errors) {

    /**
     * 复制为不可变集合。
     */
    public EtlFieldOriginResult {
        nodes = Map.copyOf(nodes);
        errors = List.copyOf(errors);
    }

    /**
     * 节点追踪结果。输出节点（写表、写文件等）没有输出字段，写入内容即其输入字段。
     *
     * @param inputs  输入字段
     * @param outputs 输出字段
     */
    public record NodeTrace(List<TracedField> inputs, List<TracedField> outputs) {

        /**
         * 复制为不可变集合。
         */
        public NodeTrace {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    /**
     * 字段及其来源。
     *
     * @param name     字段名
     * @param origins  来源；常量或生成字段为空
     * @param complete false 表示经过脚本、表达式计算等无法在设计期追溯的组件，来源可能不完整
     */
    public record TracedField(String name, List<EtlFieldOrigin> origins, boolean complete) {

        /**
         * 复制为不可变集合。
         */
        public TracedField {
            origins = List.copyOf(origins);
        }
    }
}
