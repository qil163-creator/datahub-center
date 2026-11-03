package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.*;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.*;

/** Supply 只编排服务发布/授权，提交证据、准入和数据读取由 DataForge 提供方校验。 */
@Service
public class DataApiEtlBatchService {

    @Resource
    private EtlTargetBatchApi batches;

    public record Prepared(String observedHash, DataApiDO frozen) {

    }

    /** 调用方必须在任何 ForUpdate/写操作之前调用；挂起尚未持行锁的平台事务。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Prepared prepare(DataApiDO observed, boolean preserveBinding) {
        if (observed == null || !DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(observed.getConfigType())) {
            return null;
        }
        validateMode(observed);
        var request = new EtlTargetBatchDescribeReqDTO();
        request.setBatchId(observed.getEtlBatchId());
        request.setExecutionUserId(SecurityFrameworkUtils.getLoginUserId());
        var description = batches.describe(request).getCheckedData();
        if (description == null || !Boolean.TRUE.equals(description.getAvailable())
                || !Objects.equals(description.getBatchId(), observed.getEtlBatchId())) {
            throw exception(DATA_API_CONFIG_INVALID, "指定 ETL 批次尚未通过完整提交和质量准入");
        }
        if (preserveBinding && (!Objects.equals(observed.getEtlBatchHash(), description.getPayloadHash())
                || !Objects.equals(observed.getDatabaseId(), description.getDatabaseId())
                || !Objects.equals(observed.getEtlSchemaName(), description.getSchemaName())
                || !Objects.equals(observed.getTableName(), description.getTableName()))) {
            throw exception(DATA_API_ROLLBACK_DENIED, "原发布批次绑定已失效，不能换用其他批次");
        }
        var frozen = BeanUtils.toBean(observed, DataApiDO.class);
        frozen.setDatabaseId(description.getDatabaseId());
        frozen.setTableName(description.getTableName());
        frozen.setEtlSchemaName(description.getSchemaName());
        frozen.setEtlBatchHash(description.getPayloadHash());
        frozen.setCacheEnabled(0);
        return new Prepared(observationHash(observed), frozen);
    }

    /**
     * 校验模式。
     *
     * @param api 本次使用的 API 定义
     */
    static void validateMode(DataApiDO api) {
        if (api.getEtlBatchId() == null || api.getEtlBatchId() <= 0 || !DataApiDO.API_TYPE_QUERY.equals(
                api.getApiType())
                || !DataApiDO.RESULT_FORMAT_LIST.equals(api.getResultFormat())
                || !Set.of(DataApiDO.AUTH_TYPE_APPKEY, DataApiDO.AUTH_TYPE_SIGN)
                .contains(Objects.toString(api.getAuthType(), ""))) {
            throw exception(DATA_API_CONFIG_INVALID,
                    "ETL 批次要求正数批次编号、QUERY、LIST 和应用认证；使用 afterRow 游标分页");
        }
        if (api.getRequestParamsJson() != null && !api.getRequestParamsJson().isBlank()) {
            var definitions = cn.hutool.json.JSONUtil.parseArray(api.getRequestParamsJson());
            for (Object value : definitions) {
                if (!"afterRow".equals(cn.hutool.json.JSONUtil.parseObj(value).getStr("name"))) {
                    throw exception(DATA_API_CONFIG_INVALID, "ETL 批次仅支持 afterRow 游标，不执行自定义条件参数");
                }
            }
        }
    }

    /**
     * 应用已准备。
     *
     * @param source 来源数据
     * @param prepared 已准备的执行配置或记录
     * @return 数据 API
     */
    static DataApiDO applyPrepared(DataApiDO source, Prepared prepared) {
        if (prepared == null) {
            if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(source.getConfigType())) {
                throw exception(DATA_API_PUBLISH_CONFLICT);
            }
            return source;
        }
        if (!Objects.equals(observationHash(source), prepared.observedHash())) {
            throw exception(DATA_API_PUBLISH_CONFLICT);
        }
        // 只应用提供方证明的绑定，保留锁内读取的其他业务字段。
        var result = BeanUtils.toBean(source, DataApiDO.class);
        result.setDatabaseId(prepared.frozen().getDatabaseId());
        result.setTableName(prepared.frozen().getTableName());
        result.setEtlSchemaName(prepared.frozen().getEtlSchemaName());
        result.setEtlBatchHash(prepared.frozen().getEtlBatchHash());
        result.setCacheEnabled(0);
        return result;
    }

    /**
     * 获取数据 APIETL批次的观测摘要。
     *
     * @param source 来源数据
     * @return 计算后的摘要或签名字符串
     */
    private static String observationHash(DataApiDO source) {
        // 预检并发证据覆盖完整发布快照；不改变已发布的旧合同/配置 hash 协议。
        var material = new LinkedHashMap<String, Object>();
        material.put("snapshot", DataApiContract.snapshot(source));
        material.put("draftRevision", source.getDraftRevision());
        material.put("rowVersion", source.getRowVersion());
        material.put("activeReleaseId", source.getActiveReleaseId());
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(material));
    }

    /**
     * 读取数据 API执行响应数据。
     *
     * @param api 本次使用的 API 定义
     * @param params 业务参数映射
     * @param pageSize 每页行数
     * @param actor 操作人编号
     * @return 数据 API执行响应数据
     */
    public DataApiExecuteRespVO read(DataApiDO api, Map<String, Object> params, Integer pageSize, Long actor) {
        validateMode(api);
        if (params != null && params.keySet().stream().anyMatch(key -> !"afterRow".equals(key))) {
            throw exception(DATA_API_CONFIG_INVALID, "ETL 批次仅支持 afterRow 游标参数");
        }
        if (actor == null || api.getEtlBatchHash() == null || api.getDatabaseId() == null || api.getEtlSchemaName() == null || api.getTableName() == null) {
            throw exception(DATA_API_NOT_AUTHORIZED);
        }
        long cursor;
        try {
            cursor = Long.parseLong(Objects.toString(params == null ? null : params.get("afterRow"), "0"));
        } catch (NumberFormatException invalid) {
            throw exception(DATA_API_CONFIG_INVALID, "afterRow 必须为非负整数游标");
        }
        int limit = pageSize == null ? 100 : pageSize;
        if (cursor < 0 || limit < 1 || limit > 200) {
            throw exception(DATA_API_CONFIG_INVALID, "ETL 单页限制为 1 至 200 行");
        }
        var request = new EtlTargetBatchReadReqDTO();
        request.setBatchId(api.getEtlBatchId());
        request.setExecutionUserId(actor);
        request.setExpectedPayloadHash(api.getEtlBatchHash());
        request.setExpectedDatabaseId(api.getDatabaseId());
        request.setExpectedSchemaName(api.getEtlSchemaName());
        request.setExpectedTableName(api.getTableName());
        request.setAfterRow(cursor);
        request.setLimit(limit);
        var page = batches.read(request).getCheckedData();
        if (page == null || page.getRows() == null) {
            throw exception(DATA_API_EXECUTE_FAILED, "ETL 准入读取未返回完整结果");
        }
        var result = new DataApiExecuteRespVO();
        result.setRows(page.getRows());
        result.setNextAfterRow(page.getNextAfterRow());
        result.setHasMore(page.getHasMore());
        return result;
    }
}
