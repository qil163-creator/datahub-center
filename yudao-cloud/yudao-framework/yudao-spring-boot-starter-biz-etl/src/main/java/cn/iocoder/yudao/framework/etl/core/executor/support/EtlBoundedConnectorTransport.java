package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 原生工作节点的有界连接器传输扩展，规划阶段不读取业务记录或认证材料。 */
public interface EtlBoundedConnectorTransport {
    Set<String> kinds();
    void read(String kind, JSONObject plan, Consumer<String> rows) throws Exception;
    Writer writer(String kind, JSONObject plan) throws Exception;

    /** finish 只在完整输入结束时调用；close 仅清理，不能把失败任务的暂存数据发布。 */
    interface Writer extends AutoCloseable {
        void write(Map<String, String> values) throws Exception;
        void finish() throws Exception;
        @Override void close() throws Exception;
    }
}
