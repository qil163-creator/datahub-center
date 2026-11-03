package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 队列接口，对应 OpenAPI tag：queue related operation。
 */
public class QueueApi {

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code QueueApi}。
     */
    public QueueApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * updateQueue
     * <p>更新队列</p>
     * <p>{@code PUT /queues/{id}}</p>
     * @param id 必填，队列编号
     * @param queue 必填，YARN（Hadoop）队列名称
     * @param queueName 必填，队列名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject updateQueue(Integer id, String queue, String queueName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (queue != null) {
            params.put("queue", queue);
        }
        if (queueName != null) {
            params.put("queueName", queueName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/queues/{id}", pathVars);
        return http.putForm(path, params);
    }

    /**
     * deleteQueueById
     * <p>删除队列</p>
     * <p>{@code DELETE /queues/{id}}</p>
     * @param id 必填，队列编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject deleteQueueById(Integer id) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        pathVars.put("id", id);
        String path = DolphinSchedulerHttp.fillPath("/queues/{id}", pathVars);
        return http.delete(path, params);
    }

    /**
     * queryQueueListPaging
     * <p>分页查询队列列表</p>
     * <p>{@code GET /queues}</p>
     * @param pageNo 必填，页码
     * @param searchVal 可选，搜索值
     * @param pageSize 必填，每页条数
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryQueueListPaging(Integer pageNo, String searchVal, Integer pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (pageNo != null) {
            params.put("pageNo", pageNo);
        }
        if (searchVal != null) {
            params.put("searchVal", searchVal);
        }
        if (pageSize != null) {
            params.put("pageSize", pageSize);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/queues", pathVars);
        return http.get(path, params);
    }

    /**
     * createQueue
     * <p>创建队列</p>
     * <p>{@code POST /queues}</p>
     * @param queue 必填，YARN（Hadoop）队列名称
     * @param queueName 必填，队列名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject createQueue(String queue, String queueName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (queue != null) {
            params.put("queue", queue);
        }
        if (queueName != null) {
            params.put("queueName", queueName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/queues", pathVars);
        return http.postForm(path, params);
    }

    /**
     * verifyQueue
     * <p>校验队列</p>
     * <p>{@code POST /queues/verify}</p>
     * @param queue 必填，YARN（Hadoop）队列名称
     * @param queueName 必填，队列名称
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject verifyQueue(String queue, String queueName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (queue != null) {
            params.put("queue", queue);
        }
        if (queueName != null) {
            params.put("queueName", queueName);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/queues/verify", pathVars);
        return http.postForm(path, params);
    }

    /**
     * queryList
     * <p>查询队列列表</p>
     * <p>{@code GET /queues/list}</p>
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryList() {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/queues/list", pathVars);
        return http.get(path, params);
    }

}
