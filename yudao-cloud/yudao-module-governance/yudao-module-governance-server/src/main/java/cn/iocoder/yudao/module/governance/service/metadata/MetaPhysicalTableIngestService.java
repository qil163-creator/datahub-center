package cn.iocoder.yudao.module.governance.service.metadata;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableIngestReqDTO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaTableRegisterReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaIngestEventDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaIngestEventMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.MetadataIngestErrorCodes.*;

/** 治理接收端拥有的目标修订水位与幂等收据，复用元数据登记/diff 引擎。 */
@Service
public class MetaPhysicalTableIngestService {

    @Resource
    private MetaIngestEventMapper eventMapper;

    @Resource
    private MetaImportService importService;

    @Resource
    private MetaTableMapper tableMapper;

    @Resource
    private MetaColumnMapper columnMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 核验物理表采集信封并登记元数据，处理重复接收。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long ingest(MetadataTableIngestReqDTO request) {
        boolean enveloped = validateEnvelope(request);
        String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(request));
        if (enveloped) {
            MetaIngestEventDO duplicate = eventMapper.selectEvent(request.getSource(), request.getEventId());
            if (duplicate != null) {
                return duplicateResult(duplicate, hash);
            }
        }
        MetaTableRegisterReqVO registration = registration(request);
        // 唯一跨服务读取先完成；事务内不调用 DataForge、BPM、MQ 或外部目标数据库。
        MetaImportService.RegistrationSource prepared = importService.prepareRegistration(request.getDatasourceId());
        String target = DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of(request.getDatasourceId(),
                registration.getDbName(), registration.getTableName())));

        return transactionTemplate.execute(status -> {
            if (enveloped) {
                eventMapper.lockTarget("meta-ingest-event:" + request.getSource() + ":" + request.getEventId());
            }
            eventMapper.lockTarget("meta-ingest-target:" + target);
            MetaIngestEventDO receipt = null;
            if (enveloped) {
                MetaIngestEventDO duplicate = eventMapper.selectEvent(request.getSource(), request.getEventId());
                if (duplicate != null) {
                    return duplicateResult(duplicate, hash);
                }
                MetaIngestEventDO latest = eventMapper.selectLatest(request.getSource(), target);
                if (eventMapper.selectRevision(request.getSource(), target, request.getRevision()) != null) {
                    throw exception(META_INGEST_REVISION_CONFLICT);
                }
                receipt = new MetaIngestEventDO();
                receipt.setSource(request.getSource());
                receipt.setEventId(request.getEventId());
                receipt.setRevision(request.getRevision());
                receipt.setTargetKey(target);
                receipt.setPayloadHash(hash);
                receipt.setOccurredAt(request.getOccurredAt());
                if (latest != null && latest.getRevision() > request.getRevision()) {
                    receipt.setStatus("SUPERSEDED");
                    receipt.setTableId(latest.getTableId());
                    eventMapper.insert(receipt);
                    return latest.getTableId();
                }
            }
            Long tableId = importService.registerTablePrepared(registration, prepared);
            if (request.getMasterFlag() != null) {
                tableMapper.updateById(MetaTableDO.builder().id(tableId).masterFlag(request.getMasterFlag()).build());
            }
            for (MetadataTableIngestReqDTO.Column column : request.getColumns()) {
                if (column.getStandardElementId() == null) {
                    continue;
                }
                MetaColumnDO targetColumn = columnMapper.selectByTableIdAndName(tableId, column.getColumnName().trim());
                if (targetColumn != null) {
                    columnMapper.updateById(MetaColumnDO.builder().id(targetColumn.getId())
                            .standardElementId(column.getStandardElementId()).build());
                }
            }
            if (receipt != null) {
                receipt.setStatus("APPLIED");
                receipt.setTableId(tableId);
                eventMapper.insert(receipt);
            }
            return tableId;
        });
    }

    /**
     * 读取重复采集请求对应的已有登记结果。
     */
    private Long duplicateResult(MetaIngestEventDO receipt, String hash) {
        if (!Objects.equals(receipt.getPayloadHash(), hash)) {
            throw exception(META_INGEST_EVENT_CONFLICT);
        }
        return receipt.getTableId();
    }

    /**
     * 校验物理表元数据接收的信封，不满足约束时拒绝当前操作。
     */
    private boolean validateEnvelope(MetadataTableIngestReqDTO request) {
        boolean present = request.getEventId() != null || request.getSource() != null || request.getRevision() != null
                || request.getOccurredAt() != null || request.getSchemaVersion() != null;
        if (present && (StrUtil.isBlank(request.getEventId()) || request.getEventId().length() > 128
                || StrUtil.isBlank(request.getSource()) || request.getSource().length() > 64
                || request.getRevision() == null || request.getRevision() <= 0
                || request.getOccurredAt() == null || !Objects.equals(request.getSchemaVersion(), 1))) {
            throw exception(META_INGEST_ENVELOPE_INVALID);
        }
        return present;
    }

    /**
     * 根据采集信封构造物理表元数据登记请求。
     */
    private MetaTableRegisterReqVO registration(MetadataTableIngestReqDTO request) {
        MetaTableRegisterReqVO result = new MetaTableRegisterReqVO();
        result.setDatasourceId(request.getDatasourceId());
        result.setDbName(StrUtil.blankToDefault(request.getDbName(), "default").trim());
        result.setTableName(request.getTableName().trim());
        result.setTableComment(request.getTableComment());
        result.setSourceSystemName(request.getSourceSystemName());
        result.setColumns(request.getColumns().stream().map(column -> {
            MetaTableRegisterReqVO.RegisterColumn item = new MetaTableRegisterReqVO.RegisterColumn();
            item.setColumnName(column.getColumnName());
            item.setColumnComment(column.getColumnComment());
            item.setDataType(column.getDataType());
            item.setDataLength(column.getDataLength());
            item.setDataPrecision(column.getDataPrecision());
            item.setDataScale(column.getDataScale());
            item.setNullableFlag(column.getNullableFlag());
            item.setPkFlag(column.getPkFlag());
            return item;
        }).toList());
        return result;
    }
}
