package cn.iocoder.yudao.module.supply.controller.open;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiGatewayService;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiJsonBody;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiQueryCodec;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiSignatureV2;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_REQUEST_INVALID;
import static cn.iocoder.yudao.module.supply.service.dataapi.DataApiGatewayService.*;

/**
 * 数据服务-对外网关 Controller：第三方系统通过应用凭证调用已上线的 API 服务
 *
 * 调用地址：GET/POST /supply/gateway/services/{version}{path}
 * 认证方式：NONE 不需要请求头；APPKEY 只需 X-AppKey；SIGN 需要 X-AppKey、X-Timestamp、
 * X-Nonce、X-Sign。v1 签名串为 HTTP_METHOD_API_PATH_AppKey_Timestamp_Nonce；
 * v2（X-Sign-Version=v2）使用 HMAC-SM3，覆盖 path/query/body。
 *
 * @author Li
 */
@Tag(name = "对外开放 - 数据服务网关")
@RestController
@RequestMapping("/supply/gateway")
@Slf4j
public class GatewayController {

    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Resource
    private DataApiGatewayService gatewayService;

    /**
     * 调用数据服务 API（对外，无需登录，按 API 认证方式校验应用凭证）。
     *
     * @param version 版本
     * @param request 请求参数
     * @param response 响应对象
     * @param rawBody 原始请求体
     * @return 数据 API执行响应数据
     */
    @RequestMapping(value = "/services/{version}/**",
            method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "调用数据服务 API（对外，无需登录，按 API 认证方式校验应用凭证）")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @PermitAll
    public CommonResult<DataApiExecuteRespVO> invoke(@PathVariable("version") String version,
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody(required = false) String rawBody) {
        String traceId = resolveTraceId(request.getHeader("X-Trace-Id"));
        request.setAttribute("supply.traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        String uri = request.getRequestURI();
        String marker = "/services/" + version;
        int index = uri.indexOf(marker);
        String path = index >= 0 ? uri.substring(index + marker.length()) : "";
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > DataApiSignatureV2.MAX_BODY_BYTES) {
            throw exception(DATA_API_REQUEST_INVALID, "请求体超过大小限制");
        }
        boolean v2 = DataApiSignatureV2.VERSION.equalsIgnoreCase(request.getHeader(HEADER_SIGN_VERSION));
        Map<String, Object> params;
        if (v2) {
            params = mergeV2Params(request.getQueryString(), rawBody);
        } else {
            params = new LinkedHashMap<>();
            request.getParameterMap().forEach((key, values) -> {
                if (values != null && values.length > 0) {
                    params.put(key, values[0]);
                }
            });
            if (StrUtil.isNotBlank(rawBody)) {
                params.putAll(DataApiJsonBody.parseObject(rawBody));
            }
        }
        Integer pageNo = extractInt(params.remove("pageNo"));
        Integer pageSize = extractInt(params.remove("pageSize"));
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : List.of(HEADER_APP_KEY, HEADER_TIMESTAMP, HEADER_NONCE, HEADER_SIGN,
                HEADER_SIGN_VERSION, HEADER_KEY_VERSION, "Content-Type")) {
            headers.put(name, request.getHeader(name));
        }
        log.info("[gateway][traceId={} version={} path={} method={} appKeyPresent={} v2={}]",
                traceId, version, path, request.getMethod(),
                StrUtil.isNotBlank(headers.get(HEADER_APP_KEY)), v2);
        return success(gatewayService.invoke(version, path, request.getMethod(), params,
                pageNo, pageSize, ServletUtils.getClientIP(request), request.getQueryString(),
                rawBody, headers));
    }

    /**
     * 合并版本2参数。
     *
     * @param rawQuery 原始查询字符串
     * @param rawBody 原始请求体
     * @return 处理后的键值映射
     */
    private Map<String, Object> mergeV2Params(String rawQuery, String rawBody) {
        Map<String, Object> query = DataApiSignatureV2.toParamMap(DataApiQueryCodec.parse(rawQuery));
        Map<String, Object> body = DataApiJsonBody.parseObject(rawBody);
        for (String key : body.keySet()) {
            if (query.containsKey(key)) {
                throw exception(DATA_API_REQUEST_INVALID, "query 与 body 存在同名参数：" + key);
            }
        }
        Map<String, Object> params = new LinkedHashMap<>(query);
        params.putAll(body);
        return params;
    }

    /**
     * 解析链路编号。
     *
     * @param incoming 传入
     * @return 解析链路编号文本
     */
    private String resolveTraceId(String incoming) {
        if (incoming != null && TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return IdUtil.fastSimpleUUID();
    }

    /**
     * 提取整数。
     *
     * @param value 待处理的值
     * @return 提取整数数值
     */
    private Integer extractInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

}
