package cn.iocoder.yudao.module.modeling.api.indicator;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiReqDTO;
import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiRespDTO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryRespVO;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorQueryMaskSupport;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorQueryService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 指标数据查询 RPC 实现：复用管理端的语义层查询，按调用方给出的查看主体脱敏。
 * 只返回列、行与脱敏列，不返回口径中的来源表等内部信息。
 */
@RestController
@Validated
public class IndicatorQueryApiImpl implements IndicatorQueryApi {

    @Resource
    private IndicatorQueryService queryService;

    /**
     * 查询指标值。
     */
    @Override
    public CommonResult<IndicatorQueryApiRespDTO> query(IndicatorQueryApiReqDTO request) {
        IndicatorQueryRespVO result = IndicatorQueryMaskSupport.runAs(request.getViewerUserId(),
                () -> queryService.query(toReq(request)));
        return success(toResp(result));
    }

    /**
     * 转换请求。
     */
    static IndicatorQueryReqVO toReq(IndicatorQueryApiReqDTO request) {
        IndicatorQueryReqVO req = new IndicatorQueryReqVO();
        req.setIndicatorIds(request.getIndicatorIds());
        req.setDimensions(request.getDimensions());
        if (request.getFilters() != null) {
            List<IndicatorQueryReqVO.Filter> filters = new ArrayList<>();
            request.getFilters().forEach(filter -> {
                IndicatorQueryReqVO.Filter item = new IndicatorQueryReqVO.Filter();
                item.setFieldUid(filter.getFieldUid());
                item.setValue(filter.getValue());
                filters.add(item);
            });
            req.setFilters(filters);
        }
        if (request.getStartDate() != null && request.getEndDate() != null) {
            IndicatorQueryReqVO.TimeRange range = new IndicatorQueryReqVO.TimeRange();
            range.setStart(request.getStartDate());
            range.setEnd(request.getEndDate());
            req.setTimeRange(range);
        }
        req.setTimeGrain(request.getTimeGrain());
        req.setLimit(request.getLimit());
        return req;
    }

    /**
     * 转换结果。
     */
    static IndicatorQueryApiRespDTO toResp(IndicatorQueryRespVO result) {
        IndicatorQueryApiRespDTO resp = new IndicatorQueryApiRespDTO();
        if (result.getColumns() != null) {
            result.getColumns().forEach(column -> {
                IndicatorQueryApiRespDTO.Column item = new IndicatorQueryApiRespDTO.Column();
                item.setKey(column.getKey());
                item.setTitle(column.getTitle());
                item.setKind(column.getKind());
                resp.getColumns().add(item);
            });
        }
        if (result.getRows() != null) {
            resp.setRows(result.getRows());
        }
        resp.setTruncated(result.getTruncated());
        if (result.getMaskedColumns() != null) {
            result.getMaskedColumns().forEach(column -> resp.getMaskedColumns().add(column.getName()));
        }
        return resp;
    }
}
