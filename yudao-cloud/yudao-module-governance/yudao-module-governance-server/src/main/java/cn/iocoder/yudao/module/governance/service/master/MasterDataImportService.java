package cn.iocoder.yudao.module.governance.service.master;

import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.data.ReadCellData;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterColumnVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataChangeSubmitReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataMutationRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelRespVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DateUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_DATA_ROW_INVALID;

@Service
@RequiredArgsConstructor
public class MasterDataImportService {

    private static final int MAX_ROWS = 200;
    private final MasterModelService modelService;
    private final MasterDataChangeService changeService;

    public void writeTemplate(Long modelId, HttpServletResponse response) throws IOException {
        var model = modelService.getMaintenanceModel(modelId);
        var fields = importColumns(model).stream().map(MasterColumnVO::getName).toList();
        ExcelUtils.writeTextTemplate(response, model.getTableName() + "-主数据导入模板.xlsx", "主数据", fields);
    }

    public MasterDataMutationRespVO submit(Long modelId, String requestId, MultipartFile file) {
        var columns = importColumns(modelService.getMaintenanceModel(modelId));
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024 || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw invalid("请选择不超过 5 MB 的 .xlsx 文件");
        }
        List<ExcelUtils.RawRow> sheet;
        try {
            sheet = ExcelUtils.readXlsxRows(file, MAX_ROWS + 2);
        } catch (IOException | RuntimeException ex) {
            throw invalid("工作簿无法读取，请使用下载的 .xlsx 模板");
        }
        var command = new MasterDataChangeSubmitReqVO();
        command.setModelId(modelId);
        command.setRequestId(requestId);
        command.setOperation("CREATE");
        command.setRows(records(sheet, columns));
        return changeService.submit(command);
    }

    private static List<MasterColumnVO> importColumns(MasterModelRespVO model) {
        if (!Boolean.TRUE.equals(model.getMaintenanceSupported())) {
            throw invalid("当前模型不支持数据维护");
        }
        var fields = model.getColumns().stream()
                .filter(column -> !column.getName().equals(model.getEnabledCodeField())).toList();
        if (fields.isEmpty()) {
            throw invalid("模型没有可导入的字段");
        }
        return fields;
    }

    static List<Map<String, Object>> records(List<ExcelUtils.RawRow> sheet, List<MasterColumnVO> columns) {
        if (sheet.size() < 2) throw invalid("工作簿需要包含表头和至少一行数据");
        if (sheet.size() > MAX_ROWS + 1) throw invalid("一次最多导入 200 行数据");
        var header = sheet.getFirst();
        if (header.number() != 1) throw invalid("请在第一行保留模板表头");
        Map<String, MasterColumnVO> fields = new HashMap<>();
        columns.forEach(c -> fields.put(c.getName(), c));
        Map<Integer, MasterColumnVO> positions = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        header.cells().forEach((index, cell) -> {
            if (empty(cell)) return;
            String name = cell.getStringValue();
            if (cell.getFormulaData() != null || !fields.containsKey(name)) {
                throw invalid("第 1 行第 " + (index + 1) + " 列不是当前模型的字段，请重新下载模板");
            }
            if (!seen.add(name)) throw invalid("表头字段重复：" + name);
            positions.put(index, fields.get(name));
        });
        for (MasterColumnVO c : columns) {
            if (required(c) && !seen.contains(c.getName())) throw invalid("表头缺少必填字段 " + c.getName());
        }
        if (positions.isEmpty()) throw invalid("表头没有可导入的字段");
        List<Map<String, Object>> records = new ArrayList<>();
        for (var row : sheet.subList(1, sheet.size())) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (var entry : row.cells().entrySet()) {
                if (empty(entry.getValue())) continue;
                var column = positions.get(entry.getKey());
                if (column == null) throw invalid("第 " + row.number() + " 行有数据的列缺少表头");
                values.put(column.getName(), value(row, column, entry.getValue()));
            }
            for (var column : columns) {
                if (required(column) && (values.get(column.getName()) == null
                        || values.get(column.getName()).toString().isBlank())) {
                    throw invalid("第 " + row.number() + " 行字段【" + column.getName() + "】不能为空");
                }
            }
            records.add(values);
        }
        return records;
    }

    private static boolean required(MasterColumnVO c) {
        return Integer.valueOf(1).equals(c.getPk()) || Integer.valueOf(0).equals(c.getNullable());
    }

    private static boolean empty(ReadCellData<?> cell) {
        return cell == null || cell.getType() == CellDataTypeEnum.EMPTY && cell.getFormulaData() == null;
    }

    private static String value(ExcelUtils.RawRow row, MasterColumnVO field, ReadCellData<?> cell) {
        String location = "第 " + row.number() + " 行字段【" + field.getName() + "】";
        if (cell.getFormulaData() != null) throw invalid(location + "含公式，请粘贴单元格的值");
        return switch (cell.getType()) {
            case STRING, DIRECT_STRING, RICH_TEXT_STRING -> cell.getStringValue();
            case BOOLEAN -> cell.getBooleanValue().toString();
            case NUMBER -> {
                BigDecimal number = cell.getOriginalNumberValue() == null ? cell.getNumberValue() : cell.getOriginalNumberValue();
                var format = cell.getDataFormatData();
                if (format != null && format.getIndex() != null && DateUtil.isADateFormat(format.getIndex(), format.getFormat())) {
                    LocalDateTime date = DateUtil.getLocalDateTime(number.doubleValue(), row.date1904());
                    if (date == null) throw invalid(location + "日期无效");
                    yield switch (field.getFieldType()) {
                        case ColumnMetaData.TYPE_DATE -> date.toLocalDate().toString();
                        case ColumnMetaData.TYPE_TIME -> java.sql.Time.valueOf(date.toLocalTime()).toString();
                        case ColumnMetaData.TYPE_TIMESTAMP -> java.sql.Timestamp.valueOf(date).toString();
                        default -> throw invalid(location + "应使用文本或数字格式");
                    };
                }
                yield number.toPlainString();
            }
            default -> throw invalid(location + "不是有效的文本、数字、日期或布尔值");
        };
    }

    private static ServiceException invalid(String detail) {
        return exception(MASTER_DATA_ROW_INVALID, detail);
    }
}
