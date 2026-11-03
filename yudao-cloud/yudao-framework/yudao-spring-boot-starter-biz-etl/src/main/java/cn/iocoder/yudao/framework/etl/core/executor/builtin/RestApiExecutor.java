package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.etl.core.connection.EtlApiPort;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.List;
import java.util.Map;

/**
 * Rest API 输入节点（toolCode: rest-api）
 *
 * 配置：apiConfig 节点独立的接口配置；fetchSize 每页条数。
 * 分页 API 循环取页直到返回空；非分页只取一次
 *
 * @author Li
 */
public class RestApiExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "rest-api";

    private static final int MAX_PAGES = 100_000;

    /**
     * 初始化 {@code RestApiExecutor}及其父类状态。
     */
    public RestApiExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        EtlApiPort apiPort = context.getApiPort();
        if (apiPort == null) {
            throw new IllegalStateException("当前环境未接入 API 数据端口");
        }
        var apiConfig = config().getJSONObject("apiConfig");
        if (apiConfig == null || apiConfig.isEmpty()) {
            throw new IllegalStateException(String.format("节点【%s】缺少配置：API", node.getName()));
        }
        int fetchSize = Math.max(config().getInt("fetchSize", 500), 1);
        boolean paged = apiPort.isPaged(apiConfig);
        logLine(String.format("开始拉取 API 数据（%s）", paged ? "分页" : "单次"));
        int page = 1;
        while (true) {
            checkStop();
            List<Map<String, Object>> rows = apiPort.fetchRows(apiConfig, page, fetchSize);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                readCount.incrementAndGet();
                pushRow(row);
            }
            if (!paged) {
                break;
            }
            // 返回条数不足一页视为最后一页
            if (rows.size() < fetchSize) {
                break;
            }
            if (++page > MAX_PAGES) {
                throw new IllegalStateException("分页拉取超过上限，请检查 API 分页配置是否生效");
            }
        }
        logLine(String.format("API 数据拉取完成，共 %s 行", readCount.get()));
    }

}
