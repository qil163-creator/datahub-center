package cn.iocoder.yudao.module.governance.api.standard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.standard.dto.StandardAssociationColumnRespDTO;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcFilter;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardAssociationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
public class StandardAssociationApiImpl implements StandardAssociationApi {

    private final StandardAssociationService service;

    private final HttpServletRequest servletRequest;

    /**
     * 获得标准当前已确认落标字段。
     */
    @Override
    public CommonResult<List<StandardAssociationColumnRespDTO>> getColumns(String kind, Integer id) {
        StandardTermRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(service.getColumns(kind, id));
    }
}
