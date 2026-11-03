package cn.iocoder.yudao.module.modeling.convert.planning;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;

import java.util.ArrayList;
import java.util.List;

/** 数据板块命名规则在 JSONB 存储与结构化接口之间的显式转换。 */
public final class PlanningDomainConvert {

    /**
     * 限制规划域工具的实例化，统一通过静态能力使用。
     */
    private PlanningDomainConvert() {
    }

    /**
     * 转换为数据对象。
     *
     * @param request 请求参数
     * @return 数据域
     */
    public static DataDomainDO toDataObject(DataDomainSaveReqVO request) {
        DataDomainDO row = new DataDomainDO();
        BeanUtil.copyProperties(request, row, CopyOptions.create().setIgnoreProperties("namingRules"));
        row.setBusinessOwnerIds(
                request.getBusinessOwnerIds() == null ? List.of() : new ArrayList<>(request.getBusinessOwnerIds()));
        row.setDataOwnerIds(request.getDataOwnerIds() == null ? List.of() : new ArrayList<>(request.getDataOwnerIds()));
        row.setNamingRules(JsonUtils.toJsonString(request.getNamingRules()));
        return row;
    }

    /**
     * 转换为响应。
     *
     * @param row 当前行数据
     * @return 数据域响应数据
     */
    public static DataDomainRespVO toResponse(DataDomainDO row) {
        DataDomainRespVO result = new DataDomainRespVO();
        BeanUtil.copyProperties(row, result, CopyOptions.create().setIgnoreProperties("namingRules"));
        result.setNamingRules(row.getNamingRules() == null ? List.of()
                : JsonUtils.parseArray(row.getNamingRules(), DataDomainNamingRuleVO.class));
        result.setBusinessOwnerIds(
                row.getBusinessOwnerIds() == null ? List.of() : new ArrayList<>(row.getBusinessOwnerIds()));
        result.setDataOwnerIds(row.getDataOwnerIds() == null ? List.of() : new ArrayList<>(row.getDataOwnerIds()));
        return result;
    }
}
