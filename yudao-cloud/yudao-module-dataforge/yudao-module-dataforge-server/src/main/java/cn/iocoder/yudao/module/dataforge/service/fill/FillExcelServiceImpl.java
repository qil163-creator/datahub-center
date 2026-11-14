package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillExcelImportConfirmReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillExcelImportPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmitRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDraftRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillDraftRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillOperationTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillValidationSeverityEnum;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFDataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-Excel 导入导出 Service 实现类
 *
 * @author Li
 */
@Service
public class FillExcelServiceImpl implements FillExcelService {

    /** 单次导入行数上限（一期实例规模 10000 行，导入建议分批） */
    private static final int MAX_IMPORT_ROWS = 5000;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L;

    /** 模板下拉校验的生效行数 */
    private static final int TEMPLATE_VALID_ROWS = 2000;

    private static final int MODE_APPEND = 1;

    private static final int MODE_REPLACE = 2;

    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private FillWorkItemService workItemService;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private FillDraftService draftService;

    @Resource
    private FillDraftRowMapper draftRowMapper;

    @Resource
    private FillTaskMapper taskMapper;

    /**
     * 下载 Excel 填报模板（含必填标记与字典下拉，仅输入列）
     *
     * @param workItemId 工作项编号
     * @param response 响应对象
     */
    @Override
    public void downloadTemplate(Long workItemId, HttpServletResponse response) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkViewPermission(item, userId);
        List<FieldMeta> fields = inputFields(item.getTableVersionId());

        try (Workbook book = new XSSFWorkbook()) {
            Font requiredFont = book.createFont();
            requiredFont.setFontName("微软雅黑");
            requiredFont.setColor(Font.COLOR_RED);
            XSSFSheet sheet = (XSSFSheet) book.createSheet("数据");
            Sheet dictionarySheet = book.createSheet("dictionary");
            book.setSheetHidden(book.getSheetIndex(dictionarySheet), true);
            CellStyle textStyle = book.createCellStyle();
            DataFormat format = book.createDataFormat();
            textStyle.setDataFormat(format.getFormat("@"));

            Row headerRow = sheet.createRow(0);
            for (int columnIndex = 0; columnIndex < fields.size(); columnIndex++) {
                FieldMeta field = fields.get(columnIndex);
                RichTextString header = new XSSFRichTextString(
                        field.required ? field.name + "(*)" : field.name);
                if (field.required) {
                    header.applyFont(field.name.length(), field.name.length() + 3, requiredFont);
                }
                headerRow.createCell(columnIndex).setCellValue(header);
                // Excel 下拉只支持单选；多选列按“英文逗号分隔”录入，导入时逐项校验
                if (CollUtil.isNotEmpty(field.dictOptions) && !field.multiple) {
                    createDictValidation(sheet, dictionarySheet, columnIndex, field);
                }
                sheet.setColumnWidth(columnIndex, 6000);
                sheet.setDefaultColumnStyle(columnIndex, textStyle);
            }
            writeWorkbook(response, book, buildFileName(item, "填报模板"));
        } catch (Exception ex) {
            throw exception(FILL_EXCEL_GENERATE_FAILED, ex.getMessage());
        }
    }

    /**
     * 导入预检：解析 + 逐行逐字段校验，不写草稿
     *
     * @param workItemId 工作项编号
     * @param file 文件
     * @return 填报Excel导入预览响应数据
     */
    @Override
    public FillExcelImportPreviewRespVO previewImport(Long workItemId, MultipartFile file, Map<String, String> mapping) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkEditPermission(item, userId);
        validateFile(file);
        FillTableVersionDO version = tableVersionService.getTableVersion(item.getTableVersionId());
        List<FieldMeta> fields = inputFields(item.getTableVersionId());

        List<Map<String, Object>> rows = new ArrayList<>();
        List<FillSubmitRespVO.Issue> issues = new ArrayList<>();
        List<String> matchedColumns = new ArrayList<>();
        List<String> ignoredHeaders = new ArrayList<>();
        List<FillExcelImportPreviewRespVO.HeaderMapping> headerMappings = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw exception(FILL_EXCEL_FILE_INVALID, "首行表头为空");
            }
            // 表头 → 字段映射：按中文名（容忍必填星标）或 fieldCode 匹配，未匹配列忽略
            Map<Integer, FieldMeta> columnMapping = new LinkedHashMap<>();
            for (Cell cell : headerRow) {
                String header = cellText(cell, null);
                if (StrUtil.isBlank(header)) {
                    continue;
                }
                String normalized = normalizeHeader(header);
                FieldMeta matched;
                if (mapping != null && mapping.containsKey(header)) {
                    // 手工映射优先：空串表示忽略该列
                    String target = StrUtil.trimToEmpty(mapping.get(header));
                    matched = fields.stream().filter(field -> field.fieldCode.equals(target)).findFirst().orElse(null);
                } else {
                    matched = fields.stream()
                            .filter(field -> field.name.equals(normalized) || field.fieldCode.equals(normalized))
                            .findFirst().orElse(null);
                }
                FillExcelImportPreviewRespVO.HeaderMapping headerMapping = new FillExcelImportPreviewRespVO.HeaderMapping();
                headerMapping.setHeader(header);
                headerMapping.setFieldCode(matched == null ? "" : matched.fieldCode);
                headerMappings.add(headerMapping);
                if (matched == null) {
                    ignoredHeaders.add(header);
                } else if (columnMapping.containsValue(matched)) {
                    throw exception(FILL_EXCEL_FILE_INVALID, "多个表头映射到了同一字段【" + matched.name + "】");
                } else {
                    columnMapping.put(cell.getColumnIndex(), matched);
                    matchedColumns.add(matched.name);
                }
            }
            if (columnMapping.isEmpty()) {
                throw exception(FILL_EXCEL_NO_MATCHED_COLUMN);
            }

            for (int rowNum = headerRow.getRowNum() + 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) {
                    continue;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                boolean hasValue = false;
                for (Map.Entry<Integer, FieldMeta> entry : columnMapping.entrySet()) {
                    String text = cellText(row.getCell(entry.getKey()), entry.getValue());
                    if (StrUtil.isNotBlank(text)) {
                        hasValue = true;
                        data.put(entry.getValue().fieldCode, text);
                    }
                }
                if (!hasValue) {
                    continue;
                }
                if (rows.size() >= MAX_IMPORT_ROWS) {
                    throw exception(FILL_EXCEL_ROW_LIMIT_EXCEEDED, sheet.getLastRowNum(), MAX_IMPORT_ROWS);
                }
                validateRow(fields, data, rowNum + 1, issues);
                rows.add(data);
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(FILL_EXCEL_FILE_INVALID, ex.getMessage());
        }

        // UPSERT 模板：业务键在文件内查重（跨草稿的组合重复由提交强校验兜底）
        validateBusinessKeyUniqueInFile(version, rows, issues);

        FillExcelImportPreviewRespVO respVO = new FillExcelImportPreviewRespVO();
        respVO.setTotalCount(rows.size());
        respVO.setErrorCount(issues.size());
        respVO.setMatchedColumns(matchedColumns);
        respVO.setIgnoredHeaders(ignoredHeaders);
        respVO.setHeaders(headerMappings);
        respVO.setFields(fields.stream().map(field -> {
            FillExcelImportPreviewRespVO.FieldOption option = new FillExcelImportPreviewRespVO.FieldOption();
            option.setFieldCode(field.fieldCode);
            option.setName(field.name);
            return option;
        }).toList());
        respVO.setIssues(issues);
        respVO.setRows(rows);
        return respVO;
    }

    /**
     * 导入确认：将预检行经草稿操作通道原子写入（追加或整体替换）
     *
     * @param workItemId 工作项编号
     * @param reqVO 请求参数
     * @return 填报操作应用响应数据
     */
    @Override
    public FillOperationApplyRespVO confirmImport(Long workItemId, FillExcelImportConfirmReqVO reqVO) {
        if (reqVO.getMode() != MODE_APPEND && reqVO.getMode() != MODE_REPLACE) {
            throw exception(FILL_OPERATION_INVALID, "未知导入模式 " + reqVO.getMode());
        }
        if (reqVO.getRows().size() > MAX_IMPORT_ROWS) {
            throw exception(FILL_EXCEL_ROW_LIMIT_EXCEEDED, reqVO.getRows().size(), MAX_IMPORT_ROWS);
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkEditPermission(item, userId);

        // 转为草稿操作，经 applyOperations 原子应用：版本 CAS、操作日志、协同广播全部复用
        List<FillOperationApplyReqVO.OperationItem> operations = new ArrayList<>();
        if (reqVO.getMode() == MODE_REPLACE) {
            // 只替换本单位录入的行；下级汇总生成的行由汇总维护
            for (FillDraftRowDO existing : draftRowMapper.selectListByWorkItemId(workItemId)) {
                if (existing.getSourceKind() != null) {
                    continue;
                }
                FillOperationApplyReqVO.OperationItem remove = new FillOperationApplyReqVO.OperationItem();
                remove.setClientOpId("imp-" + IdUtil.fastSimpleUUID());
                remove.setOpType(FillOperationTypeEnum.REMOVE_ROW.getType());
                remove.setRowId(existing.getRowId());
                operations.add(remove);
            }
        }
        List<String> keys = reqVO.getMode() == MODE_APPEND && CollUtil.isNotEmpty(reqVO.getDuplicateKeys())
                ? reqVO.getDuplicateKeys().stream().filter(StrUtil::isNotBlank).distinct().toList() : List.of();
        if (!keys.isEmpty()) {
            Set<String> inputCodes = inputFields(item.getTableVersionId()).stream()
                    .map(field -> field.fieldCode).collect(java.util.stream.Collectors.toSet());
            if (!inputCodes.containsAll(keys)) {
                throw exception(FILL_OPERATION_INVALID, "唯一标识必须是模板中的录入字段");
            }
        }
        List<ExistingRow> existing = keys.isEmpty() ? List.of()
                : draftRowMapper.selectListByWorkItemId(workItemId).stream()
                .filter(row -> row.getSourceKind() == null)
                .map(row -> new ExistingRow(row.getRowId(), parseRowData(row.getData()))).toList();
        ImportPlan plan = planImport(reqVO.getRows(), existing, keys,
                "OVERWRITE".equals(reqVO.getDuplicateMode()));
        for (Map<String, Object> row : plan.adds()) {
            FillOperationApplyReqVO.OperationItem add = new FillOperationApplyReqVO.OperationItem();
            add.setClientOpId("imp-" + IdUtil.fastSimpleUUID());
            add.setOpType(FillOperationTypeEnum.ADD_ROW.getType());
            add.setRowId("imp" + IdUtil.fastSimpleUUID());
            add.setValue(row);
            operations.add(add);
        }
        plan.updates().forEach((rowId, row) -> row.forEach((fieldCode, value) -> {
            FillOperationApplyReqVO.OperationItem set = new FillOperationApplyReqVO.OperationItem();
            set.setClientOpId("imp-" + IdUtil.fastSimpleUUID());
            set.setOpType(FillOperationTypeEnum.SET_CELL.getType());
            set.setRowId(rowId);
            set.setFieldCode(fieldCode);
            set.setValue(value);
            operations.add(set);
        }));
        if (operations.isEmpty()) {
            throw exception(FILL_OPERATION_INVALID, "全部数据与已有行重复，已跳过 " + plan.skipped() + " 行");
        }

        FillOperationApplyReqVO applyReqVO = new FillOperationApplyReqVO();
        applyReqVO.setExpectedVersion(reqVO.getExpectedVersion());
        applyReqVO.setClientSessionId("excel-import");
        applyReqVO.setOperations(operations);
        FillOperationApplyRespVO respVO = draftService.applyOperations(workItemId, applyReqVO);
        respVO.setAddedCount(plan.adds().size());
        respVO.setUpdatedCount(plan.updates().size());
        respVO.setSkippedCount(plan.skipped());
        return respVO;
    }

    /** 已有草稿行 */
    record ExistingRow(String rowId, Map<String, Object> data) {
    }

    /**
     * 导入计划：新增的行、按唯一标识覆盖的行（rowId → 导入值）、跳过的行数。
     */
    record ImportPlan(List<Map<String, Object>> adds, Map<String, Map<String, Object>> updates, int skipped) {
    }

    /**
     * 按唯一标识规划导入：标识全为空的行直接新增；与已有行重复时跳过或覆盖；文件内重复时，
     * 跳过模式保留第一行，覆盖模式以最后一行为准。
     *
     * @param rows 导入行
     * @param existing 已有草稿行
     * @param keys 唯一标识字段码（为空表示不查重）
     * @param overwrite 是否覆盖
     * @return 导入计划
     */
    static ImportPlan planImport(List<Map<String, Object>> rows, List<ExistingRow> existing, List<String> keys,
                                 boolean overwrite) {
        if (keys.isEmpty()) {
            return new ImportPlan(rows, Map.of(), 0);
        }
        Map<String, String> existingByKey = new java.util.HashMap<>();
        for (ExistingRow row : existing) {
            String key = keyOf(row.data(), keys);
            if (key != null) {
                existingByKey.putIfAbsent(key, row.rowId());
            }
        }
        Map<String, Map<String, Object>> addsByKey = new LinkedHashMap<>();
        List<Map<String, Object>> adds = new ArrayList<>();
        Map<String, Map<String, Object>> updates = new LinkedHashMap<>();
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            String key = keyOf(row, keys);
            if (key == null) {
                adds.add(row);
                continue;
            }
            String existingRowId = existingByKey.get(key);
            if (existingRowId != null) {
                if (overwrite) {
                    if (updates.put(existingRowId, row) != null) {
                        skipped++;
                    }
                } else {
                    skipped++;
                }
                continue;
            }
            if (addsByKey.containsKey(key)) {
                skipped++;
                if (overwrite) {
                    addsByKey.put(key, row);
                }
                continue;
            }
            addsByKey.put(key, row);
        }
        adds.addAll(addsByKey.values());
        return new ImportPlan(adds, updates, skipped);
    }

    private static String keyOf(Map<String, Object> data, List<String> keys) {
        StringBuilder key = new StringBuilder();
        boolean any = false;
        for (String code : keys) {
            String value = data.get(code) == null ? "" : String.valueOf(data.get(code)).trim();
            any |= !value.isEmpty();
            key.append(value).append('\u0001');
        }
        return any ? key.toString() : null;
    }

    /**
     * 导出当前草稿数据为 Excel
     *
     * @param workItemId 工作项编号
     * @param response 响应对象
     */
    @Override
    public void exportDraft(Long workItemId, HttpServletResponse response) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkViewPermission(item, userId);
        List<FieldMeta> fields = visibleFields(item.getTableVersionId());
        List<FillDraftRowDO> rows = draftRowMapper.selectListByWorkItemId(workItemId);
        // 公式列不存草稿，导出时按模板公式计算
        List<Map<String, Object>> rowData = new java.util.ArrayList<>(rows.size());
        rows.forEach(row -> rowData.add(parseRowData(row.getData())));
        FillTemplateEvaluator.applyFormulas(
                tableVersionService.getTableVersion(item.getTableVersionId()).getFormulaSchema(), rowData);

        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("数据");
            Row headerRow = sheet.createRow(0);
            for (int columnIndex = 0; columnIndex < fields.size(); columnIndex++) {
                headerRow.createCell(columnIndex).setCellValue(fields.get(columnIndex).name);
                sheet.setColumnWidth(columnIndex, 6000);
            }
            int rowNum = 1;
            for (Map<String, Object> data : rowData) {
                Row excelRow = sheet.createRow(rowNum++);
                for (int columnIndex = 0; columnIndex < fields.size(); columnIndex++) {
                    FieldMeta field = fields.get(columnIndex);
                    Object value = data.get(field.fieldCode);
                    if (value == null) {
                        continue;
                    }
                    Cell cell = excelRow.createCell(columnIndex);
                    // 数值字段写数值单元格，其余按文本（防公式注入：POI 文本单元格不解析公式）
                    if (field.isNumeric() && value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (field.isNumeric() && isParsableNumber(String.valueOf(value))) {
                        cell.setCellValue(Double.parseDouble(String.valueOf(value)));
                    } else {
                        cell.setCellValue(String.valueOf(value));
                    }
                }
            }
            writeWorkbook(response, book, buildFileName(item, "填报数据"));
        } catch (Exception ex) {
            throw exception(FILL_EXCEL_GENERATE_FAILED, ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- 校验

    /** 与提交强校验同源的字段级规则（REQUIRED/DICT/TYPE/MAX_LENGTH），行号为 Excel 实际行号 */
    private void validateRow(List<FieldMeta> fields, Map<String, Object> data,
            int excelRowNo, List<FillSubmitRespVO.Issue> issues) {
        for (FieldMeta field : fields) {
            Object value = data.get(field.fieldCode);
            String text = value == null ? "" : String.valueOf(value).trim();
            if (field.required && StrUtil.isBlank(text)) {
                issues.add(issue("REQUIRED", field.fieldCode, excelRowNo,
                        String.format("第 %s 行【%s】不能为空", excelRowNo, field.name)));
                continue;
            }
            if (StrUtil.isBlank(text)) {
                continue;
            }
            if (!FillFieldRuleValidator.optionsAllowed(field.dictOptions, field.multiple, text)) {
                issues.add(issue("DICT", field.fieldCode, excelRowNo,
                        String.format("第 %s 行【%s】的值不在选择范围内", excelRowNo, field.name)));
                continue;
            }
            FillValueConverter.TypeViolation typeViolation = FillValueConverter.check(field.fieldType, text);
            if (typeViolation != null) {
                issues.add(issue(typeViolation.ruleCode(), field.fieldCode, excelRowNo,
                        String.format("第 %s 行【%s】%s", excelRowNo, field.name, typeViolation.detail())));
                continue;
            }
            if (!field.isNumeric() && field.length != null && field.length > 0
                    && text.length() > field.length) {
                issues.add(issue("MAX_LENGTH", field.fieldCode, excelRowNo,
                        String.format("第 %s 行【%s】长度超过 %s", excelRowNo, field.name, field.length)));
                continue;
            }
            // 取值范围、小数位与格式规则，与提交强校验同源
            for (FillFieldRuleValidator.Violation violation : FillFieldRuleValidator.checkValue(field.schema, text)) {
                FillSubmitRespVO.Issue issue = issue(violation.ruleCode(), field.fieldCode, excelRowNo,
                        String.format("第 %s 行【%s】%s", excelRowNo, field.name, violation.detail()));
                issue.setSeverity(violation.severity());
                issues.add(issue);
            }
        }
    }

    /** UPSERT 模板的业务键在导入文件内查重（行号为解析后的数据行序号） */
    private void validateBusinessKeyUniqueInFile(FillTableVersionDO version, List<Map<String, Object>> rows,
            List<FillSubmitRespVO.Issue> issues) {
        if (!"UPSERT".equals(version.getMergeStrategy()) || StrUtil.isBlank(version.getBusinessKeyFields())) {
            return;
        }
        List<String> keyFields = StrUtil.split(version.getBusinessKeyFields(), ',');
        Map<String, Integer> seenRowNo = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> data = rows.get(index);
            StringBuilder key = new StringBuilder();
            boolean hasBlankKey = false;
            for (String keyField : keyFields) {
                Object value = data.get(keyField.trim());
                String text = value == null ? "" : String.valueOf(value).trim();
                if (text.isEmpty()) {
                    hasBlankKey = true;
                    break;
                }
                key.append(text).append('\u0001');
            }
            if (hasBlankKey) {
                continue;
            }
            Integer firstRowNo = seenRowNo.putIfAbsent(key.toString(), index + 1);
            if (firstRowNo != null) {
                issues.add(issue("BUSINESS_KEY_DUPLICATE", keyFields.get(0).trim(), index + 1,
                        String.format("数据第 %s 行与第 %s 行的业务键（%s）重复",
                                index + 1, firstRowNo, version.getBusinessKeyFields())));
            }
        }
    }

    /**
     * 获取填报Excel的问题。
     *
     * @param ruleCode 规则编码
     * @param fieldCode 字段编码
     * @param excelRowNo Excel行序号
     * @param message 消息
     * @return 问题
     */
    private FillSubmitRespVO.Issue issue(String ruleCode, String fieldCode, int excelRowNo, String message) {
        FillSubmitRespVO.Issue issue = new FillSubmitRespVO.Issue();
        issue.setRuleCode(ruleCode);
        issue.setSeverity(FillValidationSeverityEnum.ERROR.getSeverity());
        issue.setFieldCode(fieldCode);
        issue.setDisplayRowNo(excelRowNo);
        issue.setMessage(message);
        return issue;
    }

    /**
     * 校验文件。
     *
     * @param file 文件
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw exception(FILL_EXCEL_FILE_INVALID, "文件为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw exception(FILL_EXCEL_FILE_INVALID, "文件超过 10MB");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".xlsx")) {
            throw exception(FILL_EXCEL_FILE_INVALID, "仅支持 .xlsx 格式");
        }
    }

    // ---------------------------------------------------------------- Excel 读写

    /** 单元格转文本：数值去科学计数与尾零；日期按字段类型格式化；公式取缓存结果 */
    private String cellText(Cell cell, FieldMeta field) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> StrUtil.trimToNull(cell.getStringCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
                    yield LocalTime.MIDNIGHT.equals(dateTime.toLocalTime()) && (field == null || field.fieldType == 3)
                            ? dateTime.toLocalDate().toString()
                            : dateTime.format(DATE_TIME_FORMAT);
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    /**
     * 创建字典校验。
     *
     * @param sheet 工作表
     * @param dictionarySheet 字典工作表
     * @param columnIndex 列下标
     * @param field 字段
     */
    private void createDictValidation(XSSFSheet sheet, Sheet dictionarySheet, int columnIndex, FieldMeta field) {
        Row dictRow = dictionarySheet.createRow(columnIndex);
        dictRow.createCell(0).setCellValue("col_" + columnIndex);
        List<String> values = field.dictOptions;
        for (int i = 0; i < values.size(); i++) {
            dictRow.createCell(i + 1).setCellValue(values.get(i));
        }
        // 字典行引用区间：$B$n:$<end>$n
        String range = "$B$" + (columnIndex + 1) + ":$"
                + CellReference.convertNumToColString(values.size()) + "$" + (columnIndex + 1);
        XSSFDataValidationConstraint constraint = new XSSFDataValidationConstraint(
                DataValidationConstraint.ValidationType.LIST, "dictionary!" + range);
        CellRangeAddressList regions = new CellRangeAddressList(1, TEMPLATE_VALID_ROWS, columnIndex, columnIndex);
        DataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        DataValidation validation = helper.createValidation(constraint, regions);
        validation.createErrorBox("error", "请选择正确的" + field.name);
        validation.setShowErrorBox(true);
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    /**
     * 写入工作簿。
     *
     * @param response 响应对象
     * @param book 工作簿
     * @param fileName 文件名称
     */
    private void writeWorkbook(HttpServletResponse response, Workbook book, String fileName) throws Exception {
        String encoded = URLEncoder.encode(fileName + ".xlsx", StandardCharsets.UTF_8);
        response.addHeader("Content-Disposition", "attachment;filename=" + encoded);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/x-download");
        try (OutputStream out = response.getOutputStream()) {
            book.write(out);
            out.flush();
        }
    }

    /**
     * 构造文件名称。
     *
     * @param item 当前条目
     * @param suffix 后缀
     * @return 构造文件名称文本
     */
    private String buildFileName(FillWorkItemDO item, String suffix) {
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        String taskName = task != null ? task.getName() : String.valueOf(item.getTaskId());
        return taskName + "-" + StrUtil.nullToEmpty(item.getOrgName()) + "-" + suffix;
    }

    // ---------------------------------------------------------------- 字段元数据

    /** 输入列：可见且可编辑（模板与导入只涉及输入列，公式列由服务端重算） */
    private List<FieldMeta> inputFields(Long tableVersionId) {
        return parseFields(tableVersionId).stream()
                .filter(field -> !field.hidden && field.editable)
                .toList();
    }

    /** 可见列：导出含只读公式列 */
    private List<FieldMeta> visibleFields(Long tableVersionId) {
        return parseFields(tableVersionId).stream()
                .filter(field -> !field.hidden)
                .toList();
    }

    /**
     * 解析填报Excel的字段。
     *
     * @param tableVersionId 表版本编号
     * @return 解析填报Excel的字段结果集合
     */
    private List<FieldMeta> parseFields(Long tableVersionId) {
        FillTableVersionDO version = tableVersionService.getTableVersion(tableVersionId);
        List<?> rawFields = JsonUtils.parseObject(version.getFieldSchema(), List.class);
        List<FieldMeta> fields = new ArrayList<>();
        if (rawFields == null) {
            return fields;
        }
        for (Object rawField : rawFields) {
            if (!(rawField instanceof Map<?, ?> map)) {
                continue;
            }
            FieldMeta meta = new FieldMeta();
            meta.fieldCode = String.valueOf(map.get("fieldCode"));
            Object nameValue = map.get("name");
            meta.name = nameValue != null ? String.valueOf(nameValue) : meta.fieldCode;
            meta.fieldType = map.get("fieldType") instanceof Number number ? number.intValue() : -1;
            meta.required = Boolean.TRUE.equals(map.get("required"));
            meta.hidden = Boolean.TRUE.equals(map.get("hidden"));
            meta.editable = !Boolean.FALSE.equals(map.get("editable"));
            meta.length = map.get("length") instanceof Number number ? number.intValue() : null;
            meta.multiple = Boolean.TRUE.equals(map.get("multiple"));
            Map<String, Object> schema = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> schema.put(String.valueOf(key), value));
            meta.schema = schema;
            if (map.get("dictOptions") instanceof List<?> options) {
                meta.dictOptions = options.stream().map(String::valueOf).toList();
            } else {
                meta.dictOptions = List.of();
            }
            fields.add(meta);
        }
        return fields;
    }

    /** 去除模板表头中的必填星标（(*)、*、空白） */
    private String normalizeHeader(String header) {
        return header.replace("(*)", "").replace("（*）", "").replace("*", "").trim();
    }

    /**
     * 解析行数据。
     *
     * @param dataJson 数据JSON
     * @return 处理后的键值映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRowData(String dataJson) {
        if (StrUtil.isBlank(dataJson)) {
            return Map.of();
        }
        Map<String, Object> data = JsonUtils.parseObject(dataJson, Map.class);
        return data != null ? data : Map.of();
    }

    /**
     * 判断是否满足可解析数值条件。
     *
     * @param text 待处理文本
     * @return 判断结果
     */
    private boolean isParsableNumber(String text) {
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static class FieldMeta {

        private String fieldCode;

        private String name;

        private int fieldType;

        private boolean required;

        private boolean hidden;

        private boolean editable;

        private Integer length;

        private List<String> dictOptions;

        /** 多选字段：取值为英文逗号分隔的多个可选项 */
        private boolean multiple;

        /** 发布版本固化的完整字段 schema，供取值范围/格式规则校验使用 */
        private Map<String, Object> schema = Map.of();

        /** dbswitch 逻辑类型：1-小数 5-整数 6-长整型 */
        private boolean isNumeric() {
            return fieldType == 1 || fieldType == 5 || fieldType == 6;
        }
    }

}
