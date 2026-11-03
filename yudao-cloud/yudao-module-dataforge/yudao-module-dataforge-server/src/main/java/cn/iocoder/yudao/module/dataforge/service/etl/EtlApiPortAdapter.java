package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.connection.EtlApiPort;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.dataapi.DataApiClient;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;

/**
 * ETL 引擎 API 数据端口适配器：将节点独立配置交给已有接口客户端取数。
 *
 * @author Li
 */
@Component
public class EtlApiPortAdapter implements EtlApiPort {

    @Resource
    private DataApiClient dataApiClient;

    /**
     * 获取行。
     *
     * @param configuration 处理配置
     * @param page 分页
     * @param pageSize 每页行数
     * @return 行结果集合
     */
    @Override
    public List<Map<String, Object>> fetchRows(JSONObject configuration, int page, int pageSize) {
        DataApiSaveReqVO api = configuration.toBean(DataApiSaveReqVO.class);
        if (!api.isRequestConfigurationValid()) {
            throw new IllegalStateException("接口配置不完整");
        }
        boolean paged = Integer.valueOf(1).equals(api.getIfPage());
        if (!paged && page > 1) {
            return List.of();
        }
        return dataApiClient.fetchRows(api, paged ? page : null, pageSize);
    }

    /**
     * 判断是否满足分页条件。
     *
     * @param configuration 处理配置
     * @return 判断结果
     */
    @Override
    public boolean isPaged(JSONObject configuration) {
        return Integer.valueOf(1).equals(configuration.getInt("ifPage"));
    }

    /**
     * 读取ETLAPI端口适配器的列集合。
     *
     * @param configuration 处理配置
     * @return ETLAPI端口适配器的列集合结果集合
     */
    @Override
    public List<EtlFieldMeta> getColumns(JSONObject configuration) {
        DataApiSaveReqVO api = configuration.toBean(DataApiSaveReqVO.class);
        if (CollUtil.isEmpty(api.getDataColumns())) {
            return List.of();
        }
        return convertList(api.getDataColumns(),
                column -> new EtlFieldMeta(column.getFieldName(),
                        column.getFieldType() == null ? "-" : String.valueOf(column.getFieldType())));
    }

}
