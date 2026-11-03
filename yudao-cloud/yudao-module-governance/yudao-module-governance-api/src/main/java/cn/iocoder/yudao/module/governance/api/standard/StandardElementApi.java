package cn.iocoder.yudao.module.governance.api.standard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardElementRespDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;

/**
 * 标准数据元 RPC 接口：供建模等模块比对字段绑定的标准是否变更
 *
 * @author Li
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 标准数据元")
public interface StandardElementApi {

    String PREFIX = ApiConstants.PREFIX + "/standard-element";

    /**
     * 按编号批量获得标准数据元精简信息。
     */
    @GetMapping(PREFIX + "/list-by-ids")
    @Operation(summary = "按编号批量获得标准数据元精简信息")
    CommonResult<List<StandardElementRespDTO>> getElementListByIds(
            @RequestParam("ids") Collection<Long> ids);

}
