package cn.iocoder.yudao.framework.etl.core.connection;

import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta;
import cn.hutool.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * API 数据端口：Rest API 输入节点传递自己的请求配置，取数由宿主适配器完成。
 *
 * @author Li
 */
public interface EtlApiPort {

    /**
     * 拉取一页数据（非分页 API 忽略 page/pageSize，且第 2 页起应返回空）
     *
     * @param apiConfig 节点独立保存的接口配置，字段契约由宿主适配器解释
     * @param page     页码（从 1 开始）
     * @param pageSize 每页条数
     */
    List<Map<String, Object>> fetchRows(JSONObject apiConfig, int page, int pageSize);

    /**
     * API 是否分页
     */
    boolean isPaged(JSONObject apiConfig);

    /**
     * API 定义的返回字段（schema 推导用）
     */
    List<EtlFieldMeta> getColumns(JSONObject apiConfig);

}
