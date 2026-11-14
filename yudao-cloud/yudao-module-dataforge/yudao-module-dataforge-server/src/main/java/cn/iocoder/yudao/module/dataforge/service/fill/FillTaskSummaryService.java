package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanPeriodSummaryRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTaskSummaryRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_PLAN_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_TASK_NOT_EXISTS;

/**
 * 在线填报-结果汇总：按组织、按期次查看报送情况，导出未报单位清单与审批通过的数据
 *
 * 数据范围：与延期、催办等任务写操作一致，仅任务发起者（计划创建人或任务创建人）与超级管理员可查看。
 * 合计只统计当前提交审批通过的组织；数值以十进制字符串返回，避免前端精度丢失。
 *
 * @author Li
 */
@Service
public class FillTaskSummaryService {

    /** 期次汇总最多展示的期数 */
    static final int PERIOD_LIMIT = 12;

    /** 汇总数据导出的行数上限，超过时提示改用合并后的物理表查询 */
    static final int EXPORT_ROW_LIMIT = 200_000;

    /** 未报：尚未提交（待填报、填报中、整改中），或结束填报时被取消 */
    static final Set<Integer> UNREPORTED_STATUSES = Set.of(
            FillWorkItemStatusEnum.PENDING.getStatus(), FillWorkItemStatusEnum.FILLING.getStatus(),
            FillWorkItemStatusEnum.REWORKING.getStatus(), FillWorkItemStatusEnum.CANCELLED.getStatus());

    private static final Set<Integer> NUMERIC_TYPES = Set.of(
            FillValueConverter.TYPE_DECIMAL, FillValueConverter.TYPE_INTEGER, FillValueConverter.TYPE_LONG);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillPlanMapper planMapper;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillSubmissionMapper submissionMapper;

    @Resource
    private FillSubmissionRowMapper submissionRowMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private FillTaskAccessGuard accessGuard;

    // ---------------------------------------------------------------- 任务汇总

    /**
     * 任务汇总：各组织报送状态、审批通过数据的行数与数值字段合计。
     *
     * @param taskId 任务编号
     * @return 汇总结果
     */
    public FillTaskSummaryRespVO getSummary(Long taskId) {
        FillTaskDO task = checkTask(taskId);
        List<SummaryField> fields = numericFields(task.getTableVersionId());
        List<FillWorkItemDO> items = sortedItems(taskId);
        Map<Long, FillSubmissionDO> approved = approvedSubmissions(items);

        Map<String, BigDecimal> grand = new LinkedHashMap<>();
        List<FillTaskSummaryRespVO.OrgRow> orgs = new ArrayList<>();
        int unreported = 0;
        for (FillWorkItemDO item : items) {
            FillTaskSummaryRespVO.OrgRow row = new FillTaskSummaryRespVO.OrgRow();
            row.setWorkItemId(item.getId());
            row.setOrgName(item.getOrgName());
            row.setStatus(item.getStatus());
            row.setOverdue(item.getOverdue());
            row.setParentWorkItemId(item.getParentWorkItemId());
            row.setLevel(item.getLevel() == null ? 0 : item.getLevel());
            FillSubmissionDO submission = approved.get(item.getId());
            row.setApproved(submission != null);
            row.setTotals(Map.of());
            if (submission != null) {
                row.setSubmitTime(submission.getSubmitTime());
                Map<String, BigDecimal> totals = new LinkedHashMap<>();
                int rowCount = 0;
                for (FillSubmissionRowDO dataRow : submissionRowMapper.selectListBySubmissionId(submission.getId())) {
                    rowCount++;
                    accumulate(totals, fields, parseRow(dataRow));
                }
                row.setRowCount(rowCount);
                row.setTotals(toStrings(totals));
                // 下级数据已汇入上级：只累加顶层，避免重复计算
                if (item.getParentWorkItemId() == null) {
                    totals.forEach((code, value) -> grand.merge(code, value, BigDecimal::add));
                }
            }
            if (UNREPORTED_STATUSES.contains(item.getStatus())) {
                unreported++;
            }
            orgs.add(row);
        }

        FillTaskSummaryRespVO resp = new FillTaskSummaryRespVO();
        resp.setTaskId(task.getId());
        resp.setTaskName(task.getName());
        resp.setPeriodKey(task.getPeriodKey());
        resp.setFields(fields.stream().map(field -> {
            FillTaskSummaryRespVO.Field vo = new FillTaskSummaryRespVO.Field();
            vo.setFieldCode(field.fieldCode());
            vo.setName(field.name());
            return vo;
        }).toList());
        resp.setOrgs(orgs);
        resp.setTotals(toStrings(grand));
        resp.setApprovedCount((int) items.stream()
                .filter(item -> item.getParentWorkItemId() == null && approved.containsKey(item.getId())).count());
        resp.setUnreportedCount(unreported);
        return resp;
    }

    /**
     * 导出未报单位清单（待填报、填报中、整改中、结束填报时取消）。
     *
     * @param taskId 任务编号
     * @param response 响应
     */
    public void exportUnreported(Long taskId, HttpServletResponse response) throws IOException {
        FillTaskDO task = checkTask(taskId);
        List<FillWorkItemDO> items = sortedItems(taskId).stream()
                .filter(item -> UNREPORTED_STATUSES.contains(item.getStatus())).toList();
        Map<Long, String> assignees = assigneeNames(items.stream().map(FillWorkItemDO::getId).toList());
        try (SXSSFWorkbook book = new SXSSFWorkbook(200)) {
            Sheet sheet = book.createSheet("未报单位");
            writeRow(sheet, 0, List.of("报送组织", "组织编码", "上级单位", "层级", "状态", "是否逾期", "截止时间",
                    "当前填报人", "最后修改时间"));
            Map<Long, String> names = sortedItems(taskId).stream().collect(Collectors.toMap(FillWorkItemDO::getId,
                    item -> StrUtil.nullToEmpty(item.getOrgName()), (a, b) -> a));
            int rowIndex = 1;
            for (FillWorkItemDO item : items) {
                FillWorkItemStatusEnum status = FillWorkItemStatusEnum.of(item.getStatus());
                writeRow(sheet, rowIndex++, List.of(
                        StrUtil.nullToEmpty(item.getOrgName()),
                        StrUtil.nullToEmpty(item.getOrgCode()),
                        item.getParentWorkItemId() == null ? "发起者直接下发"
                                : names.getOrDefault(item.getParentWorkItemId(), ""),
                        String.valueOf(item.getLevel() == null ? 0 : item.getLevel()),
                        status != null ? status.getName() : String.valueOf(item.getStatus()),
                        Integer.valueOf(1).equals(item.getOverdue()) ? "是" : "否",
                        format(item.getDeadlineTime()),
                        assignees.getOrDefault(item.getId(), ""),
                        format(item.getLastModifyTime())));
            }
            write(response, book, task.getName() + "-未报单位清单");
        }
    }

    /**
     * 导出审批通过的汇总数据：每行带报送组织，列为模板可见字段。
     *
     * @param taskId 任务编号
     * @param response 响应
     */
    public void exportData(Long taskId, HttpServletResponse response) throws IOException {
        FillTaskDO task = checkTask(taskId);
        List<SummaryField> fields = visibleFields(task.getTableVersionId());
        List<FillWorkItemDO> all = sortedItems(taskId);
        // 只导出顶层：下级数据已包含在上级的提交中；每行按来源组织标注报送单位
        List<FillWorkItemDO> items = all.stream().filter(item -> item.getParentWorkItemId() == null).toList();
        Map<Long, FillSubmissionDO> approved = approvedSubmissions(items);
        Map<Long, FillWorkItemDO> byOrg = new HashMap<>();
        all.forEach(item -> byOrg.putIfAbsent(item.getReportingOrgId(), item));
        try (SXSSFWorkbook book = new SXSSFWorkbook(200)) {
            Sheet sheet = book.createSheet("汇总数据");
            List<String> header = new ArrayList<>(List.of("报送组织", "组织编码"));
            fields.forEach(field -> header.add(field.name()));
            writeRow(sheet, 0, header);
            int rowIndex = 1;
            for (FillWorkItemDO item : items) {
                FillSubmissionDO submission = approved.get(item.getId());
                if (submission == null) {
                    continue;
                }
                for (FillSubmissionRowDO dataRow : submissionRowMapper.selectListBySubmissionId(submission.getId())) {
                    if (rowIndex > EXPORT_ROW_LIMIT) {
                        writeRow(sheet, rowIndex, List.of("数据超过 " + EXPORT_ROW_LIMIT + " 行，已截断，请到合并后的数据表查询"));
                        write(response, book, task.getName() + "-汇总数据");
                        return;
                    }
                    Map<String, Object> data = parseRow(dataRow);
                    FillWorkItemDO source = dataRow.getSourceOrgId() == null ? item
                            : byOrg.getOrDefault(dataRow.getSourceOrgId(), item);
                    List<String> values = new ArrayList<>(List.of(
                            StrUtil.nullToEmpty(source.getOrgName()), StrUtil.nullToEmpty(source.getOrgCode())));
                    fields.forEach(field -> values.add(cellText(data.get(field.fieldCode()))));
                    writeRow(sheet, rowIndex++, values);
                }
            }
            write(response, book, task.getName() + "-汇总数据");
        }
    }

    // ---------------------------------------------------------------- 期次汇总

    /**
     * 计划期次汇总：最近若干期 × 报送组织的状态矩阵。
     *
     * @param planId 计划编号
     * @return 期次汇总
     */
    public FillPlanPeriodSummaryRespVO getPeriodSummary(Long planId) {
        FillPlanDO plan = planMapper.selectById(planId);
        if (plan == null) {
            throw exception(FILL_PLAN_NOT_EXISTS);
        }
        accessGuard.checkPlanInitiator(plan);
        List<FillTaskDO> tasks = taskMapper.selectLatestByPlanId(planId, PERIOD_LIMIT);
        List<FillPlanPeriodSummaryRespVO.Period> periods = new ArrayList<>();
        Map<Long, FillPlanPeriodSummaryRespVO.OrgRow> orgs = new LinkedHashMap<>();
        for (FillTaskDO task : tasks) {
            List<FillWorkItemDO> items = workItemMapper.selectListByTaskId(task.getId());
            FillPlanPeriodSummaryRespVO.Period period = new FillPlanPeriodSummaryRespVO.Period();
            period.setTaskId(task.getId());
            period.setPeriodKey(task.getPeriodKey());
            period.setStatus(task.getStatus());
            period.setTotalCount(items.size());
            period.setMergedCount((int) items.stream()
                    .filter(item -> FillWorkItemStatusEnum.MERGED.getStatus().equals(item.getStatus())).count());
            periods.add(period);
            for (FillWorkItemDO item : items) {
                FillPlanPeriodSummaryRespVO.OrgRow org = orgs.computeIfAbsent(item.getReportingOrgId(), id -> {
                    FillPlanPeriodSummaryRespVO.OrgRow row = new FillPlanPeriodSummaryRespVO.OrgRow();
                    row.setReportingOrgId(id);
                    row.setOrgName(item.getOrgName());
                    row.setStatuses(new LinkedHashMap<>());
                    row.setOverdue(new LinkedHashMap<>());
                    return row;
                });
                org.getStatuses().put(task.getId(), item.getStatus());
                org.getOverdue().put(task.getId(), item.getOverdue() == null ? 0 : item.getOverdue());
            }
        }
        FillPlanPeriodSummaryRespVO resp = new FillPlanPeriodSummaryRespVO();
        resp.setPeriods(periods);
        resp.setOrgs(orgs.values().stream()
                .sorted(Comparator.comparing(row -> StrUtil.nullToEmpty(row.getOrgName()))).toList());
        return resp;
    }

    // ---------------------------------------------------------------- 内部

    private FillTaskDO checkTask(Long taskId) {
        FillTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw exception(FILL_TASK_NOT_EXISTS);
        }
        accessGuard.checkInitiator(task);
        return task;
    }

    /** 按树形顺序：每个实例之后紧跟它的各级下级；同级按组织路径、名称排序 */
    private List<FillWorkItemDO> sortedItems(Long taskId) {
        Comparator<FillWorkItemDO> order = Comparator.comparing((FillWorkItemDO item) -> StrUtil.nullToEmpty(item.getOrgPath()))
                .thenComparing(item -> StrUtil.nullToEmpty(item.getOrgName()));
        List<FillWorkItemDO> items = workItemMapper.selectListByTaskId(taskId);
        Set<Long> ids = items.stream().map(FillWorkItemDO::getId).collect(Collectors.toSet());
        Map<Long, List<FillWorkItemDO>> byParent = items.stream()
                .filter(item -> item.getParentWorkItemId() != null && ids.contains(item.getParentWorkItemId()))
                .collect(Collectors.groupingBy(FillWorkItemDO::getParentWorkItemId));
        List<FillWorkItemDO> result = new ArrayList<>();
        java.util.Deque<FillWorkItemDO> stack = new java.util.ArrayDeque<>();
        List<FillWorkItemDO> roots = items.stream()
                .filter(item -> item.getParentWorkItemId() == null || !ids.contains(item.getParentWorkItemId()))
                .sorted(order.reversed()).toList();
        roots.forEach(stack::push);
        Set<Long> visited = new java.util.HashSet<>();
        while (!stack.isEmpty()) {
            FillWorkItemDO item = stack.pop();
            if (!visited.add(item.getId())) {
                continue;
            }
            result.add(item);
            byParent.getOrDefault(item.getId(), List.of()).stream().sorted(order.reversed()).forEach(stack::push);
        }
        return result;
    }

    /** 工作项编号 → 当前审批通过的提交（零上报也算，行数为 0） */
    private Map<Long, FillSubmissionDO> approvedSubmissions(List<FillWorkItemDO> items) {
        List<Long> ids = items.stream().map(FillWorkItemDO::getCurrentSubmissionId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, FillSubmissionDO> result = new HashMap<>();
        for (FillSubmissionDO submission : submissionMapper.selectByIds(ids)) {
            if (FillApprovalStatusEnum.APPROVED.getStatus().equals(submission.getApprovalStatus())) {
                result.put(submission.getWorkItemId(), submission);
            }
        }
        return result;
    }

    private Map<Long, String> assigneeNames(Collection<Long> workItemIds) {
        if (workItemIds.isEmpty()) {
            return Map.of();
        }
        return assigneeMapper.selectValidListByWorkItemIds(workItemIds).stream()
                .collect(Collectors.groupingBy(FillAssigneeDO::getWorkItemId, LinkedHashMap::new,
                        Collectors.mapping(assignee -> StrUtil.blankToDefault(assignee.getUserName(),
                                String.valueOf(assignee.getUserId())), Collectors.joining("、"))));
    }

    record SummaryField(String fieldCode, String name) {
    }

    /** 参与合计的字段：可见的数值字段（不含人员、部门等编号类字段） */
    private List<SummaryField> numericFields(Long tableVersionId) {
        return parseFields(tableVersionId, true);
    }

    private List<SummaryField> visibleFields(Long tableVersionId) {
        return parseFields(tableVersionId, false);
    }

    private List<SummaryField> parseFields(Long tableVersionId, boolean numericOnly) {
        FillTableVersionDO version = tableVersionService.getTableVersion(tableVersionId);
        List<?> raw = version == null ? null : JsonUtils.parseObject(version.getFieldSchema(), List.class);
        List<SummaryField> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map) || Boolean.TRUE.equals(map.get("hidden"))
                    || map.get("fieldCode") == null) {
                continue;
            }
            int type = map.get("fieldType") instanceof Number number ? number.intValue() : -1;
            if (numericOnly && (!NUMERIC_TYPES.contains(type) || map.get("refKind") != null)) {
                continue;
            }
            String code = String.valueOf(map.get("fieldCode"));
            result.add(new SummaryField(code, map.get("name") != null ? String.valueOf(map.get("name")) : code));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRow(FillSubmissionRowDO row) {
        Map<String, Object> data = row.getData() == null ? null : JsonUtils.parseObject(row.getData(), Map.class);
        return data != null ? data : Map.of();
    }

    /**
     * 累加一行的数值字段；空值与无法解析的值跳过（提交时已做类型校验，这里只做防御）。
     */
    static void accumulate(Map<String, BigDecimal> totals, List<SummaryField> fields, Map<String, Object> data) {
        for (SummaryField field : fields) {
            Object value = data.get(field.fieldCode());
            if (value == null || StrUtil.isBlank(String.valueOf(value))) {
                continue;
            }
            try {
                BigDecimal number = value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value).trim());
                totals.merge(field.fieldCode(), number, BigDecimal::add);
            } catch (NumberFormatException ignored) {
                // 非数值跳过
            }
        }
    }

    private static Map<String, String> toStrings(Map<String, BigDecimal> totals) {
        Map<String, String> result = new LinkedHashMap<>();
        totals.forEach((code, value) -> result.put(code, value.stripTrailingZeros().toPlainString()));
        return result;
    }

    private static String cellText(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
    }

    private static String format(LocalDateTime time) {
        return time == null ? "" : TIME_FORMAT.format(time);
    }

    private static void writeRow(Sheet sheet, int index, List<String> values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.size(); i++) {
            row.createCell(i).setCellValue(values.get(i));
        }
    }

    private static void write(HttpServletResponse response, Workbook book, String fileName) throws IOException {
        String encoded = URLEncoder.encode(fileName + ".xlsx", StandardCharsets.UTF_8);
        response.addHeader("Content-Disposition", "attachment;filename=" + encoded);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/x-download");
        try (OutputStream out = response.getOutputStream()) {
            book.write(out);
            out.flush();
        }
    }

}
