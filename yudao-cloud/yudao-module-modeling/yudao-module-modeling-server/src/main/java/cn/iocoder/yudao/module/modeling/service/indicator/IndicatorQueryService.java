package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryMetaReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryMetaRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryRespVO;

/**
 * 指标语义层：按维度、时间范围和粒度查出指标值。
 */
public interface IndicatorQueryService {

    /**
     * 查询指标值。
     *
     * @param request 请求参数
     * @return 列、行与生效口径
     */
    IndicatorQueryRespVO query(IndicatorQueryReqVO request);

    /**
     * 查询所选指标可共同使用的维度字段与时间能力。
     *
     * @param request 请求参数
     * @return 可选维度
     */
    IndicatorQueryMetaRespVO meta(IndicatorQueryMetaReqVO request);
}
