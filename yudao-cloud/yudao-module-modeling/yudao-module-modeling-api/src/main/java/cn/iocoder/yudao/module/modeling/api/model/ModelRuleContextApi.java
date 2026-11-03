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

/** 数据元规则候选专用通道，不改变已有模型字段同步客户端。 */
@FeignClient(name = ApiConstants.NAME, contextId = "modelRuleContextApi",
        url = "${yudao.modeling.rule-context.client.url:}", configuration = ModelRuleContextClientConfiguration.class)
@Tag(name = "RPC 服务 - 数据元规则模型字段候选")
public interface ModelRuleContextApi {

    String PATH = ApiConstants.PREFIX + "/model-field/rule-context-field-list";

    /**
     * 查询数据元已绑定模型的完整字段，供授权用户配置组合规则。
     *
     * @param standardId 标准编号
     * @return 数据元已绑定模型的完整字段，供授权用户配置组合规则结果集合
     */
    @GetMapping(PATH)
    @Operation(summary = "查询数据元已绑定模型的完整字段，供授权用户配置组合规则")
    CommonResult<List<ModelStandardFieldRespDTO>> getRuleContextFieldList(@RequestParam("standardId") Long standardId);
}
