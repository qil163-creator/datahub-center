package cn.iocoder.yudao.module.asset.service.dataexport;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.http.HttpUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataexport.vo.DataExportRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataexport.vo.DataExportSubmitReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryExecuteReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryResultRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataexport.DataExportRequestDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataexport.DataExportRequestMapper;
import cn.iocoder.yudao.module.asset.service.dataquery.DataQueryService;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.converters.longconverter.LongStringConverter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_EXPORT_EXPIRED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_EXPORT_FORBIDDEN;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_EXPORT_NOT_EXISTS;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_EXPORT_PROCESS_FAILED;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_EXPORT_STATE_INVALID;

/**
 * 数据查询结果导出：申请 → BPM 审批 → 有效期内一次性下载。
 * <p>
 * 状态机：PENDING →（审批通过）APPROVED →（下载）DOWNLOADED；PENDING → REJECTED / CANCELED；流程未能发起 → FAILED。
 * 所有迁移按“当前状态 + 修订号”CAS，BPM 回调重复、乱序都只会生效一次；回调内容不被信任，状态以向 BPM 回查为准。
 * 下载时重新执行查询，沿用数据查询的访问校验与批准列，并按 DATA_EXPORT 场景（导出豁免口径）动态脱敏；
 * 不保存导出文件和查询结果。数据范围：申请人只能查看、撤回、下载本人的申请。
 */
@Slf4j
@Service
public class DataExportService {

    /** BPM 流程定义 Key；流程需在 BPM 中按此 Key 建模，审批节点使用默认拒绝策略。 */
    public static final String PROCESS_KEY = "asset_data_export";
    /** 批准后可下载的时长。 */
    static final long DOWNLOAD_HOURS = 24;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private DataExportRequestMapper exportMapper;
    @Resource
    private DataQueryService dataQueryService;
    @Resource
    private BpmProcessInstanceApi processInstanceApi;

    /**
     * 提交导出申请：先做访问校验（不读数据），落库后以申请人身份发起审批流程。
     */
    public Long submit(DataExportSubmitReqVO req) {
        Long userId = requireUser();
        String[] target = dataQueryService.checkExportable(query(req.getDatasourceId(), req.getSql(), req.getLimit()));
        DataExportRequestDO record = new DataExportRequestDO();
        record.setUserId(userId);
        record.setDatasourceId(req.getDatasourceId());
        record.setSchemaName(target[0]);
        record.setTableName(target[1]);
        record.setSqlText(req.getSql());
        record.setRowLimit(req.getLimit());
        record.setReason(req.getReason().trim());
        record.setStatus(DataExportRequestDO.STATUS_PENDING);
        record.setRevision(0);
        exportMapper.insert(record);
        // 申请已落库再跨服务发起流程：流程发起失败时申请记为 FAILED，不留下无人审批的 PENDING。
        String processInstanceId;
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("exportId", record.getId());
            variables.put("datasourceId", record.getDatasourceId());
            variables.put("tableName", target[0] + "." + target[1]);
            variables.put("rowLimit", record.getRowLimit());
            variables.put("reason", record.getReason());
            BpmProcessInstanceCreateReqDTO create = new BpmProcessInstanceCreateReqDTO();
            create.setProcessDefinitionKey(PROCESS_KEY);
            create.setBusinessKey(String.valueOf(record.getId()));
            create.setVariables(variables);
            processInstanceId = processInstanceApi.createProcessInstance(userId, create).getCheckedData();
        } catch (RuntimeException failure) {
            log.warn("[submit][导出审批流程发起失败 exportId={} failureType={}]", record.getId(),
                    failure.getClass().getSimpleName());
            exportMapper.transit(record.getId(), DataExportRequestDO.STATUS_PENDING, DataExportRequestDO.STATUS_FAILED,
                    "审批流程未能发起", null, null);
            throw exception(DATA_EXPORT_PROCESS_FAILED, "请确认已在流程管理中发布 Key 为 " + PROCESS_KEY + " 的流程");
        }
        exportMapper.bindProcess(record.getId(), processInstanceId);
        return record.getId();
    }

    /**
     * 处理 BPM 状态事件：以向 BPM 回查到的状态为准，只从 PENDING 迁移一次。
     */
    public void handleBpmStatus(BpmProcessInstanceStatusEvent event) {
        if (event == null || !PROCESS_KEY.equals(event.getProcessDefinitionKey()) || StrUtil.isBlank(event.getId())
                || !StrUtil.isNumeric(event.getBusinessKey())) {
            return;
        }
        DataExportRequestDO record = exportMapper.selectById(Long.valueOf(event.getBusinessKey()));
        if (record == null || (record.getProcessInstanceId() != null
                && !record.getProcessInstanceId().equals(event.getId()))) {
            return;
        }
        Integer actual = processInstanceApi.getProcessInstanceStatus(event.getId()).getCheckedData();
        if (actual == null || !actual.equals(event.getStatus())) {
            // 回调内容与 BPM 实际状态不符：不信任，等待对账或下一次回调。
            log.warn("[handleBpmStatus][回调状态与流程实际状态不一致，忽略 exportId={}]", record.getId());
            return;
        }
        if (record.getProcessInstanceId() == null) {
            exportMapper.bindProcess(record.getId(), event.getId());
        }
        String reason = StrUtil.maxLength(StrUtil.nullToEmpty(event.getReason()), 500);
        LocalDateTime now = LocalDateTime.now();
        switch (actual) {
            case 2 -> exportMapper.transit(record.getId(), DataExportRequestDO.STATUS_PENDING,
                    DataExportRequestDO.STATUS_APPROVED, reason, now, now.plusHours(DOWNLOAD_HOURS));
            case 3 -> exportMapper.transit(record.getId(), DataExportRequestDO.STATUS_PENDING,
                    DataExportRequestDO.STATUS_REJECTED, reason, null, null);
            case 4 -> exportMapper.transit(record.getId(), DataExportRequestDO.STATUS_PENDING,
                    DataExportRequestDO.STATUS_CANCELED, reason, null, null);
            default -> {
                // 审批中：无需处理
            }
        }
    }

    /**
     * 申请人撤回审批中的申请。
     */
    public void cancel(Long id) {
        DataExportRequestDO record = own(id);
        if (!DataExportRequestDO.STATUS_PENDING.equals(record.getStatus())) {
            throw exception(DATA_EXPORT_STATE_INVALID, record.getStatus());
        }
        if (record.getProcessInstanceId() != null) {
            processInstanceApi.cancelProcessInstanceByStartUser(record.getUserId(), record.getProcessInstanceId(),
                    "申请人撤回导出申请").getCheckedData();
        }
        exportMapper.transit(id, DataExportRequestDO.STATUS_PENDING, DataExportRequestDO.STATUS_CANCELED,
                "申请人撤回", null, null);
    }

    /**
     * 本人的申请分页。
     */
    public PageResult<DataExportRespVO> myPage(PageParam page) {
        PageResult<DataExportRequestDO> result = exportMapper.selectMyPage(requireUser(), page);
        List<DataExportRespVO> list = new ArrayList<>(result.getList().size());
        for (DataExportRequestDO record : result.getList()) {
            DataExportRespVO vo = cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(record,
                    DataExportRespVO.class);
            vo.setTableName(StrUtil.isBlank(record.getSchemaName()) ? record.getTableName()
                    : record.getSchemaName() + "." + record.getTableName());
            list.add(vo);
        }
        return new PageResult<>(list, result.getTotal());
    }

    /**
     * 生成导出文件（一次性）：重新执行查询并脱敏，文件带导出人水印，标记已下载后才返回内容。
     */
    public ExportFile download(Long id) {
        DataExportRequestDO record = own(id);
        if (!DataExportRequestDO.STATUS_APPROVED.equals(record.getStatus())) {
            throw exception(DATA_EXPORT_STATE_INVALID, record.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        if (record.getExpireTime() == null || !record.getExpireTime().isAfter(now)) {
            throw exception(DATA_EXPORT_EXPIRED);
        }
        DataQueryResultRespVO result = dataQueryService.executeForExport(
                query(record.getDatasourceId(), record.getSqlText(), record.getRowLimit()));
        byte[] content = excel(record, result, watermark(record, now));
        int rows = result.getRows() == null ? 0 : result.getRows().size();
        if (exportMapper.markDownloaded(record.getId(), record.getRevision(), now, rows) != 1) {
            // 并发下载或刚好过期：只有一个请求能拿到文件。
            throw exception(DATA_EXPORT_STATE_INVALID, "已被下载或已过期");
        }
        return new ExportFile("数据导出_" + record.getId() + ".xlsx", content, rows);
    }

    /**
     * 导出文件。
     */
    public record ExportFile(String filename, byte[] content, int rows) {

        /** 编码后的文件名。 */
        public String encodedFilename() {
            return HttpUtils.encodeUtf8(filename);
        }
    }

    // ==================== 内部 ====================

    /**
     * 构造 Excel：首行为合并的水印说明，第二行为列名（已脱敏列加标注）。
     */
    static byte[] excel(DataExportRequestDO record, DataQueryResultRespVO result, String watermark) {
        List<DataQueryResultRespVO.Column> columns = result.getColumns() == null ? List.of() : result.getColumns();
        Set<String> masked = new HashSet<>();
        if (result.getMaskedColumns() != null) {
            result.getMaskedColumns().forEach(column -> masked.add(column.getName()));
        }
        List<List<String>> head = new ArrayList<>();
        for (DataQueryResultRespVO.Column column : columns) {
            String label = StrUtil.blankToDefault(column.getLabel(), column.getName());
            head.add(List.of(watermark, masked.contains(column.getName()) ? label + "（已脱敏）" : label));
        }
        if (head.isEmpty()) {
            head.add(List.of(watermark, "无数据"));
        }
        List<List<Object>> data = new ArrayList<>();
        for (Map<String, Object> row : result.getRows() == null ? List.<Map<String, Object>>of() : result.getRows()) {
            List<Object> values = new ArrayList<>(columns.size());
            for (DataQueryResultRespVO.Column column : columns) {
                Object value = row.get(column.getName());
                values.add(value == null ? null : String.valueOf(value));
            }
            data.add(values);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FastExcelFactory.write(out).registerConverter(new LongStringConverter())
                .head(head).sheet("导出" + record.getId()).doWrite(data);
        return out.toByteArray();
    }

    /**
     * 水印：导出人、时间与申请编号，用于泄露溯源。
     */
    static String watermark(DataExportRequestDO record, LocalDateTime now) {
        String nickname = StrUtil.blankToDefault(SecurityFrameworkUtils.getLoginUserNickname(), "用户");
        return "导出人：" + nickname + "（" + record.getUserId() + "）  导出时间：" + TIME.format(now)
                + "  申请编号：" + record.getId() + "  仅限审批用途，禁止外传";
    }

    /**
     * 本人的申请。
     */
    private DataExportRequestDO own(Long id) {
        Long userId = requireUser();
        DataExportRequestDO record = id == null ? null : exportMapper.selectById(id);
        if (record == null) {
            throw exception(DATA_EXPORT_NOT_EXISTS);
        }
        if (!Objects.equals(record.getUserId(), userId)) {
            throw exception(DATA_EXPORT_FORBIDDEN);
        }
        return record;
    }

    /**
     * 当前用户。
     */
    private static Long requireUser() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            throw exception(DATA_EXPORT_FORBIDDEN);
        }
        return userId;
    }

    /**
     * 构造查询请求。
     */
    private static DataQueryExecuteReqVO query(Long datasourceId, String sql, Integer limit) {
        DataQueryExecuteReqVO query = new DataQueryExecuteReqVO();
        query.setDatasourceId(datasourceId);
        query.setSql(sql);
        query.setLimit(limit);
        return query;
    }
}
