package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlWebServiceRows;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationWebServiceMetadataReqVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationTaskMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 任务身份和资源授权由业务层校验，WSDL 技术解析复用 ETL 组件。 */
@Service
public class IntegrationWebServiceMetadataService {
    @Resource private IntegrationTaskMapper tasks;
    @Resource private IntegrationDataxBindingsProperties bindings;
    @Resource private ExecutionAccessService access;

    public EtlWebServiceRows.Metadata inspect(IntegrationWebServiceMetadataReqVO req) {
        access.requireUserPermission(SecurityFrameworkUtils.getLoginUserId(), "dataforge:integration:query");
        var task = tasks.selectById(req.getId());
        if (task == null) throw exception(INTEGRATION_TASK_NOT_EXISTS);
        if (!Objects.equals(task.getRevision(), req.getRevision())) throw exception(INTEGRATION_CONFLICT);
        if (!"SPARK".equals(task.getEngine()) || !"OFFLINE".equals(task.getMode()))
            throw exception(INTEGRATION_CONFIG_INVALID, "WebService字段分析仅适用于Spark离线任务");
        try {
            rejectCredentialQuery(req.getApiUrl());
            Set<String> allowed = bindings.requireMetadata(task.getId(), "read-webservice", req.getResourceBindingId());
            var binding = bindings.require(task.getId(), "read-webservice", null, req.getResourceBindingId());
            return EtlWebServiceRows.inspect(req.getApiUrl(), allowed, Set.copyOf(binding.addresses()));
        } catch (IllegalArgumentException invalid) {
            throw exception(INTEGRATION_CONFIG_INVALID, invalid.getMessage());
        }
    }

    public record ApiMetadata(java.util.List<cn.iocoder.yudao.framework.etl.core.file.FileSchemaReader.Field> fields,
                              boolean partial, java.util.List<String> warnings) { }

    /** 仅对获控制面授权的无认证 GET 接口采样，复用原生请求准入和文件组件的精确数值类型推导。 */
    public ApiMetadata sample(cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationApiMetadataReqVO req) {
        access.requireUserPermission(SecurityFrameworkUtils.getLoginUserId(), "dataforge:integration:query");
        var task = tasks.selectById(req.getId());
        if (task == null) throw exception(INTEGRATION_TASK_NOT_EXISTS);
        if (!Objects.equals(task.getRevision(), req.getRevision())) throw exception(INTEGRATION_CONFLICT);
        if (!"SPARK".equals(task.getEngine()) || !"OFFLINE".equals(task.getMode()))
            throw exception(INTEGRATION_CONFIG_INVALID, "API字段采样仅适用于Spark离线任务");
        try {
            var api = cn.hutool.json.JSONUtil.parseObj(req.getApiConfig());
            if (api.toString().length() > 500000) throw new IllegalArgumentException("接口字段分析配置超过预算");
            if (!"GET".equals(api.getStr("requestMethod")) || !"none".equals(api.getStr("bodyType", "none"))
                    || !api.getStr("bodyRaw", "").isBlank()) throw new IllegalArgumentException("字段采样仅支持无请求体且无需认证的GET请求");
            rejectCredentialQuery(api.getStr("requestUrl", ""));
            var allowed = bindings.requireMetadata(task.getId(), "rest-api", req.getResourceBindingId());
            var binding = bindings.require(task.getId(), "rest-api", null, req.getResourceBindingId());
            var probe = cn.hutool.json.JSONUtil.parseObj(api.toString());
            probe.set("dataColumns", java.util.List.of(java.util.Map.of("fieldName", "dh_metadata_probe", "fieldType", 12)));
            var values = cn.hutool.json.JSONUtil.createObj().set("apiConfig", probe).set("resourceBindingId", req.getResourceBindingId())
                    .set("fetchSize", 32).set("maxPages", 1).set("maxRows", 10000).set("maxResponseBytes", 262144);
            cn.iocoder.yudao.framework.etl.core.compile.EtlNativeHttpCompiler.compile(
                    cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.valueOf(task.getEngine()), false,
                    "metadata", "rest-api", values, binding, Set.of("DH_READ_API"), Set.of());
            var rows = cn.iocoder.yudao.framework.etl.core.executor.support.EtlHttpRows.sampleRows(values, allowed);
            var parsed = cn.iocoder.yudao.framework.etl.core.file.FileSchemaReader.jsonl(String.join("\n", rows).getBytes(StandardCharsets.UTF_8));
            var warnings = new java.util.ArrayList<>(parsed.warnings().stream().filter(warning -> warning.startsWith("字段【")).toList());
            warnings.add("仅采样首个GET响应的前32行，不返回样例数据；类型来自样本，长度、精度和必填约束保持未知。");
            return new ApiMetadata(parsed.fields(), true, java.util.List.copyOf(warnings));
        } catch (IllegalArgumentException invalid) {
            throw exception(INTEGRATION_CONFIG_INVALID, invalid.getMessage());
        } catch (java.io.IOException invalid) {
            throw exception(INTEGRATION_CONFIG_INVALID, "接口采样字段格式无法分析");
        }
    }

    private static void rejectCredentialQuery(String address) {
        String query = URI.create(address).getRawQuery();
        if (query == null) return;
        for (String entry : query.split("&")) {
            String name = URLDecoder.decode(entry.split("=", 2)[0], StandardCharsets.UTF_8);
            if (name.matches("(?i).*(password|passwd|secret|token|api[-_]?key|access[-_]?key|authorization|credential).*"))
                throw new IllegalArgumentException("元数据地址不能包含认证参数，请使用无凭据文档地址");
        }
    }
}
