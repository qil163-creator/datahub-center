package cn.iocoder.yudao.module.modeling.api.model;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.api.model.dto.ModelStandardFieldRespDTO;
import cn.iocoder.yudao.module.modeling.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 模型字段 RPC 接口：供治理模块查询绑定了指定数据标准的模型字段（设计态落标）
 *
 * @author Li
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 模型字段")
public interface ModelFieldApi {

    String PREFIX = ApiConstants.PREFIX + "/model-field";

    /**
     * 查询绑定了指定数据标准的模型字段（读取当前模型字段）。
     *
     * @param standardId 标准编号
     * @return 绑定了指定数据标准的模型字段（读取当前模型字段）结果集合
     */
    @GetMapping(PREFIX + "/list-by-standard-id")
    @Operation(summary = "查询绑定了指定数据标准的模型字段（读取当前模型字段）")
    CommonResult<List<ModelStandardFieldRespDTO>> getFieldListByStandardId(
            @RequestParam("standardId") Long standardId);

    @GetMapping(PREFIX + "/list-by-code-table-id")
    @Operation(summary = "查询直接绑定代码表的模型字段")
    CommonResult<List<ModelStandardFieldRespDTO>> getFieldListByCodeTableId(
            @RequestParam("codeTableId") Long codeTableId);

    /**
     * 查询绑定了任意数据标准的模型字段（读取当前模型字段，供全局落标对账）。
     *
     * @return 标准边界字段列表
     */
    @GetMapping(PREFIX + "/list-standard-bound")
    @Operation(summary = "查询绑定了任意数据标准的模型字段（读取当前模型字段，供全局落标对账）")
    CommonResult<List<ModelStandardFieldRespDTO>> getStandardBoundFieldList();

}
