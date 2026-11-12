package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.modeling.api.indicator.IndicatorQueryApi;
import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiReqDTO;
import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiRespDTO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_EXECUTE_FAILED;

/**
 * 指标接口执行：按发布时固定的口径调用 modeling 指标语义层，时间范围与维度过滤只取声明的请求参数。
 * <p>
 * 结果按调用主体（应用授权的权限主体或管理端试调人）动态脱敏；modeling 或脱敏服务不可用时不返回数据。
 */
@Slf4j
@Service
public class DataApiIndicatorQueryService {

    private static final int DEFAULT_LIMIT = 200;

    @Resource
    private IndicatorQueryApi indicatorQueryApi;

    /**
     * 执行指标接口。
     *
     * @param pageSize      调用方请求的条数，可空；不超过发布时的上限
     * @param viewerUserId 结果查看主体
     */
    public DataApiExecuteRespVO execute(DataApiDO api, Map<String, Object> params, Integer pageSize,
                                        Long viewerUserId) {
        DataApiIndicatorConfig config = DataApiIndicatorConfig.parse(api.getIndicatorConfigJson());
        IndicatorQueryApiReqDTO request = new IndicatorQueryApiReqDTO();
        request.setViewerUserId(viewerUserId);
        request.setIndicatorIds(config.indicatorIds());
        request.setDimensions(config.dimensions());
        request.setTimeGrain(config.timeGrain());
        int cap = config.limit() == null ? DEFAULT_LIMIT : config.limit();
        request.setLimit(pageSize == null || pageSize <= 0 ? cap : Math.min(pageSize, cap));
        String start = DataApiIndicatorConfig.text(params, config.startParam());
        String end = DataApiIndicatorConfig.text(params, config.endParam());
        if (start != null && end != null) {
            request.setStartDate(start);
            request.setEndDate(end);
        }
        if (config.filters() != null) {
            List<IndicatorQueryApiReqDTO.Filter> filters = new ArrayList<>();
            for (DataApiIndicatorConfig.FilterParam mapping : config.filters()) {
                String value = DataApiIndicatorConfig.text(params, mapping.param());
                if (value != null) {
                    IndicatorQueryApiReqDTO.Filter filter = new IndicatorQueryApiReqDTO.Filter();
                    filter.setFieldUid(mapping.fieldUid());
                    filter.setValue(value);
                    filters.add(filter);
                }
            }
            request.setFilters(filters);
        }
        IndicatorQueryApiRespDTO result;
        try {
            result = indicatorQueryApi.query(request).getCheckedData();
        } catch (ServiceException business) {
            throw exception(DATA_API_EXECUTE_FAILED, business.getMessage());
        } catch (RuntimeException unavailable) {
            log.warn("[execute][指标服务不可用 apiId={} failureType={}]", api.getId(),
                    unavailable.getClass().getSimpleName());
            throw exception(DATA_API_EXECUTE_FAILED, "指标服务暂不可用");
        }
        if (result == null) {
            throw exception(DATA_API_EXECUTE_FAILED, "指标服务未返回结果");
        }
        List<Map<String, Object>> rows = new ArrayList<>(result.getRows().size());
        result.getRows().forEach(row -> rows.add(new LinkedHashMap<>(row)));
        DataApiExecuteRespVO resp = new DataApiExecuteRespVO();
        resp.setRows(rows);
        resp.setTotal((long) rows.size());
        resp.setHasMore(Boolean.TRUE.equals(result.getTruncated()));
        return resp;
    }
}
