package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.BasicTableMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.module.dataforge.service.fill.FillMergeServiceImpl;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTableVersionService;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTablePageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableColumnMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据填报-填报表（模板）Service 实现类
 *
 * 模板定义 + 目标物理表两层模型：物理表建在填报表绑定的数据源，建表 DDL 复用 dbswitch；
 * 数据录入一律走在线填报审批链路（草稿-快照-审批-合并入库），旧直写数据方法已移除
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class FillTableServiceImpl implements FillTableService {

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    /**
     * 表名/字段名格式：小写字母开头，小写字母数字下划线
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final Pattern TYPE_DIMENSIONS = Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");

    private static final int RELEASED = 1;

    private static final int UNRELEASED = 0;

    /**
     * Excel 模板下拉校验的生效行数
     */
    private static final int TEMPLATE_VALID_ROWS = 10000;

    @Resource
    private FillTableMapper fillTableMapper;

    @Resource
    private FillTableColumnMapper columnMapper;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private AccessDatabaseResolver databaseResolver;

    @Resource
    private DeptApi deptApi;

    @Resource
    private AdminUserApi adminUserApi;

    // ==================== 表定义 CRUD ====================

    /**
     * 创建填报表。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createFillTable(FillTableSaveReqVO createReqVO) {
        validateSaveReq(createReqVO);
        FillTableDO fillTable = BeanUtils.toBean(createReqVO, FillTableDO.class);
        fillTable.setStatus(UNRELEASED);
        fillTableMapper.insert(fillTable);
        return fillTable.getId();
    }

    /**
     * 更新填报表（已发布需先取消发布）。
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateFillTable(FillTableSaveReqVO updateReqVO) {
        FillTableDO old = validateFillTableExists(updateReqVO.getId());
        validateNotReleased(old);
        validateSaveReq(updateReqVO);
        fillTableMapper.updateById(BeanUtils.toBean(updateReqVO, FillTableDO.class));
    }

    /**
     * 校验保存。
     *
     * @param reqVO 请求参数
     */
    private void validateSaveReq(FillTableSaveReqVO reqVO) {
        if (!NAME_PATTERN.matcher(reqVO.getTableEngName()).matches()) {
            throw exception(FILL_TABLE_ENG_NAME_INVALID);
        }
        FillTableDO exists = fillTableMapper.selectByEngName(reqVO.getTableEngName());
        if (exists != null && !exists.getId().equals(reqVO.getId())) {
            throw exception(FILL_TABLE_ENG_NAME_DUPLICATE);
        }
        DatabaseDO database = databaseMapper.selectById(reqVO.getDatabaseId());
        if (database == null) {
            throw exception(FILL_TABLE_DATABASE_NOT_EXISTS);
        }
    }

    /**
     * 删除填报表（级联删除字段配置）。
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFillTable(Long id) {
        FillTableDO fillTable = validateFillTableExists(id);
        validateNotReleased(fillTable);
        fillTableMapper.deleteById(id);
        columnMapper.deleteByTableId(id);
    }

    /**
     * 获得填报表。
     *
     * @param id 记录编号
     * @return 填报表响应数据
     */
    @Override
    public FillTableRespVO getFillTable(Long id) {
        FillTableDO fillTable = validateFillTableExists(id);
        return CollUtil.getFirst(convertToRespList(Collections.singletonList(fillTable)));
    }

    /**
     * 获得填报表分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 填报表分页结果
     */
    @Override
    public PageResult<FillTableRespVO> getFillTablePage(FillTablePageReqVO pageReqVO) {
        PageResult<FillTableDO> pageResult = fillTableMapper.selectPage(pageReqVO);
        return new PageResult<>(convertToRespList(pageResult.getList()), pageResult.getTotal());
    }

    /**
     * 转换目标列表。
     *
     * @param list 待处理列表
     * @return 转换目标列表结果集合
     */
    private List<FillTableRespVO> convertToRespList(List<FillTableDO> list) {
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Map<Long, DatabaseDO> databaseMap = convertMap(
                databaseMapper.selectByIds(convertSet(list, FillTableDO::getDatabaseId)), DatabaseDO::getId);
        Set<Long> orgIds = convertSet(list, FillTableDO::getOrgId);
        Map<Long, DeptRespDTO> deptMap = CollUtil.isEmpty(orgIds)
                ? Collections.emptyMap() : deptApi.getDeptMap(orgIds);
        Set<Long> userIds = convertSet(list, FillTableDO::getReleaseUserId);
        Map<Long, AdminUserRespDTO> userMap = CollUtil.isEmpty(userIds)
                ? Collections.emptyMap() : adminUserApi.getUserMap(userIds);
        return convertList(list, fillTable -> {
            FillTableRespVO vo = BeanUtils.toBean(fillTable, FillTableRespVO.class);
            DatabaseDO database = databaseMap.get(fillTable.getDatabaseId());
            if (database != null) {
                vo.setDatabaseName(database.getName());
            }
            DeptRespDTO dept = fillTable.getOrgId() == null ? null : deptMap.get(fillTable.getOrgId());
            if (dept != null) {
                vo.setOrgName(dept.getName());
            }
            AdminUserRespDTO user = fillTable.getReleaseUserId() == null
                    ? null : userMap.get(fillTable.getReleaseUserId());
            if (user != null) {
                vo.setReleaseUserName(user.getNickname());
            }
            return vo;
        });
    }

    // ==================== 发布 / 取消发布 ====================

    /**
     * 发布：按同一份字段配置构建快照，并创建或校验目标物理表。
     *
     * @param id 记录编号
     */
    @Override
    public void releaseFillTable(Long id) {
        FillTableDO fillTable = validateFillTableExists(id);
        List<FillTableColumnDO> columns = columnMapper.selectListByTableId(id);
        if (CollUtil.isEmpty(columns)) {
            throw exception(FILL_TABLE_NO_COLUMN);
        }
        FillTableVersionDO snapshot = tableVersionService.buildReleaseSnapshot(fillTable, columns);
        List<ColumnDescription> columnDescriptions = new ArrayList<>();
        List<String> pkNames = new ArrayList<>();
        for (FillTableColumnDO column : columns) {
            ColumnDescription description = column.toColumnDescription();
            columnDescriptions.add(description);
            if (description.isPk()) {
                pkNames.add(description.getFieldName());
            }
        }
        // 系统来源列（V1.0 §21.3）：合并写入强制携带，支撑来源追踪与三类合并策略的幂等清理
        columnDescriptions.add(buildSystemColumn(FillMergeServiceImpl.SYS_COL_ORG_ID,
                ColumnMetaData.TYPE_BIGNUMBER, null, "来源报送组织编号（系统列）"));
        columnDescriptions.add(buildSystemColumn(FillMergeServiceImpl.SYS_COL_PERIOD_KEY,
                ColumnMetaData.TYPE_STRING, 32, "数据所属期（系统列）"));
        columnDescriptions.add(buildSystemColumn(FillMergeServiceImpl.SYS_COL_SUBMISSION_ID,
                ColumnMetaData.TYPE_BIGNUMBER, null, "来源提交快照编号（系统列）"));
        columnDescriptions.add(buildSystemColumn(FillMergeServiceImpl.SYS_COL_ROW_ID,
                ColumnMetaData.TYPE_STRING, 64, "来源行标识（系统列）"));
        AccessDatabaseResolver.ConnectionInfo conn = resolveConnection(fillTable);
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        String tableName = fillTable.getTableEngName();
        AbstractDatabase dialect = dbSwitchRuntime.products().metadata(conn.getProductType());
        var existingTable = metaDataService.queryTableList(
                        conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema()).stream()
                .filter(table -> tableName.equals(table.getTableName()))
                .findFirst();
        if (existingTable.isPresent()) {
            if (existingTable.get().isViewTable()) {
                throw exception(FILL_TABLE_TARGET_SCHEMA_MISMATCH, tableName, "同名对象是视图");
            }
            BasicTableMetadata actual = metaDataService.queryTableColumnsAndKeys(
                    conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema(), tableName);
            validateTargetSchema(tableName, dialect, columnDescriptions, pkNames, actual);
        } else {
            // UUID 主键由应用生成字符串，DDL 不使用数据库自增。
            List<String> ddlList = GenerateSqlUtils.getDDLCreateTableSQL(
                    dialect, columnDescriptions, pkNames, conn.getSchema(), tableName,
                    fillTable.getTableCnName(), false, null);
            for (String sql : ddlList) {
                metaDataService.executeSql(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), sql);
            }
        }
        tableVersionService.completeTableRelease(snapshot);
    }

    private void validateTargetSchema(String tableName, AbstractDatabase dialect, List<ColumnDescription> expected,
                                      List<String> primaryKeys, BasicTableMetadata actual) {
        Map<String, ColumnDescription> actualColumns = convertMap(actual.columns(), ColumnDescription::getFieldName);
        for (ColumnDescription column : expected) {
            String name = column.getFieldName();
            ColumnDescription target = actualColumns.remove(name);
            if (target == null) {
                throw exception(FILL_TABLE_TARGET_SCHEMA_MISMATCH, tableName, "缺少字段【" + name + "】");
            }
            if (!matchesTargetColumn(dialect, column, target, primaryKeys)) {
                throw exception(FILL_TABLE_TARGET_SCHEMA_MISMATCH, tableName, "字段【" + name + "】的类型或约束不同");
            }
        }
        if (!actualColumns.isEmpty()) {
            throw exception(FILL_TABLE_TARGET_SCHEMA_MISMATCH, tableName, "存在未配置的字段【"
                    + String.join("、", actualColumns.keySet()) + "】");
        }
        if (!primaryKeys.equals(actual.primaryKeys())) {
            throw exception(FILL_TABLE_TARGET_SCHEMA_MISMATCH, tableName, "主键字段不同");
        }
    }

    private boolean matchesTargetColumn(AbstractDatabase dialect, ColumnDescription expected,
                                        ColumnDescription actual, List<String> primaryKeys) {
        String name = expected.getFieldName();
        String definition = dialect.getFieldDefinition(expected.getMetaData(), primaryKeys, false, false, false).strip();
        String identifier = dialect.quoteIdentifier(name);
        if (!definition.startsWith(identifier)) {
            return false;
        }
        String declaredType = definition.substring(identifier.length()).strip();
        boolean notNull = declaredType.endsWith(" NOT NULL");
        if (notNull) {
            declaredType = declaredType.substring(0, declaredType.length() - " NOT NULL".length()).stripTrailing();
        }
        boolean nullable = !notNull && !primaryKeys.contains(name);
        String type = SqlTypeSemantics.canonicalName(declaredType);
        if (!type.equals(SqlTypeSemantics.canonicalName(SqlTypeSemantics.metadataTypeName(actual)))
                || nullable != actual.isNullable() || actual.isAutoIncrement()) {
            return false;
        }
        // JDBC 对字符类型报告长度，对定点数报告总精度和小数位，对时间类型报告小数位。
        var dimensions = TYPE_DIMENSIONS.matcher(declaredType);
        if (!dimensions.find()) {
            return true;
        }
        int size = Integer.parseInt(dimensions.group(1));
        if ("time".equals(type) || "timestamp".equals(type)) {
            return size == actual.getScaleSize();
        }
        return size == actual.getPrecisionSize()
                && (!"decimal".equals(type) || actual.getScaleSize()
                == (dimensions.group(2) == null ? 0 : Integer.parseInt(dimensions.group(2))));
    }

    /**
     * 取消发布：仅将模板置回未发布，不删除物理表及其数据；
     *  物理表退役属于独立的高权限操作，需检查活动任务、历史合并与下游依赖
     *
     * @param id 记录编号
     */
    @Override
    public void cancelFillTable(Long id) {
        validateFillTableExists(id);
        // 只回退发布状态，绝不删除物理表：表内可能承载历史正式数据；
        // 物理表退役属于独立高权限操作，须检查活动任务、历史合并、数据资产与下游依赖后另行执行
        fillTableMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(FillTableDO.class)
                .set(FillTableDO::getStatus, UNRELEASED)
                .set(FillTableDO::getReleaseTime, null)
                .set(FillTableDO::getReleaseUserId, null)
                .eq(FillTableDO::getId, id));
    }

    // ==================== Excel 模板 ====================

    /**
     * 下载 Excel 模板（含必填标记与选择值下拉校验）
     *
     * @param id 记录编号
     * @param response 响应对象
     */
    @Override
    public void downloadImportTemplate(Long id, HttpServletResponse response) {
        FillTableDO fillTable = validateFillTableExists(id);
        List<FillTableColumnDO> columns = columnMapper.selectListByTableId(id);
        if (CollUtil.isEmpty(columns)) {
            throw exception(FILL_TABLE_NO_COLUMN);
        }
        try (Workbook book = new XSSFWorkbook()) {
            Font font = book.createFont();
            font.setFontName("微软雅黑");
            font.setColor(Font.COLOR_RED);
            XSSFSheet sheet = (XSSFSheet) book.createSheet(fillTable.getTableEngName());
            Row headerRow = sheet.createRow(0);
            // 隐藏字典页存放选择值，供下拉校验引用
            String dictionaryName = "dictionary";
            Sheet dictionarySheet = book.createSheet(dictionaryName);
            book.setSheetHidden(book.getSheetIndex(dictionarySheet), true);
            CellStyle textStyle = book.createCellStyle();
            DataFormat format = book.createDataFormat();
            textStyle.setDataFormat(format.getFormat("@"));
            int columnIndex = 0;
            for (FillTableColumnDO column : columns) {
                boolean required = (column.getNullable() != null && column.getNullable() == 0)
                        || (column.getPk() != null && column.getPk() == 1);
                RichTextString header = new XSSFRichTextString(
                        required ? column.getCnName() + "(*)" : column.getCnName());
                if (required) {
                    header.applyFont(column.getCnName().length(), column.getCnName().length() + 3, font);
                }
                headerRow.createCell(columnIndex).setCellValue(header);
                if (isEnumColumn(column)) {
                    List<String> values = Arrays.asList(column.getFillValues().split(","));
                    createValidationByFormula(sheet, dictionarySheet, dictionaryName, columnIndex,
                            values, columnIndex, "请选择正确的" + column.getCnName());
                }
                sheet.setColumnWidth(columnIndex, 6000);
                sheet.setDefaultColumnStyle(columnIndex, textStyle);
                columnIndex++;
            }
            String fileName = URLEncoder.encode(fillTable.getTableEngName() + ".xlsx", StandardCharsets.UTF_8);
            response.addHeader("Content-Disposition", "attachment;filename=" + fileName);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/x-download");
            try (OutputStream out = response.getOutputStream()) {
                book.write(out);
                out.flush();
            }
        } catch (Exception ex) {
            throw exception(FILL_TABLE_IMPORT_FILE_INVALID, "生成模板失败：" + ex.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 校验填报表存在性。
     *
     * @param id 记录编号
     * @return 填报表
     */
    private FillTableDO validateFillTableExists(Long id) {
        FillTableDO fillTable = fillTableMapper.selectById(id);
        if (fillTable == null) {
            throw exception(FILL_TABLE_NOT_EXISTS);
        }
        return fillTable;
    }

    /**
     * 校验未已发布。
     *
     * @param fillTable 填报表
     */
    private void validateNotReleased(FillTableDO fillTable) {
        if (fillTable.getStatus() != null && fillTable.getStatus() == RELEASED) {
            throw exception(FILL_TABLE_RELEASED_CANNOT_MODIFY);
        }
    }

    /**
     * 解析连接。
     *
     * @param fillTable 填报表
     * @return 连接信息
     */
    private AccessDatabaseResolver.ConnectionInfo resolveConnection(FillTableDO fillTable) {
        return databaseResolver.resolve(fillTable.getDatabaseId(), FILL_TABLE_DATABASE_NOT_EXISTS);
    }

    /**
     * 构造系统列。
     *
     * @param name 名称
     * @param valType 值类型
     * @param length 长度
     * @param remark 备注
     * @return 列描述
     */
    private ColumnDescription buildSystemColumn(String name, int valType, Integer length, String remark) {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName(name);
        description.setLabelName(name);
        description.setValType(valType);
        if (length != null) {
            description.setLength(length);
        }
        description.setNullable(true);
        description.setRemarks(remark);
        description.setPk(false);
        description.setAutoIncrement(false);
        return description;
    }

    /**
     * 判断是否满足列条件。
     *
     * @param column 列
     * @return 判断结果
     */
    private boolean isEnumColumn(FillTableColumnDO column) {
        return column.getFillType() != null && column.getFillType() == 2
                && StrUtil.isNotBlank(column.getFillValues());
    }

    /**
     * 在模板 sheet 上为选择型字段创建下拉校验，选项存放在隐藏的字典 sheet 中
     */
    private void createValidationByFormula(XSSFSheet sheet, Sheet dictionarySheet, String dictionaryName,
            int rowId, List<String> values, int columnIndex, String errorMsg) {
        Row dictRow = dictionarySheet.createRow(rowId);
        dictRow.createCell(0).setCellValue("col_" + columnIndex);
        for (int i = 0; i < values.size(); i++) {
            dictRow.createCell(i + 1).setCellValue(values.get(i));
        }
        String range = buildRange(1, rowId + 1, values.size());
        String formula = dictionaryName + "!" + range;
        XSSFDataValidationConstraint constraint =
                new XSSFDataValidationConstraint(DataValidationConstraint.ValidationType.LIST, formula);
        CellRangeAddressList regions =
                new CellRangeAddressList(1, TEMPLATE_VALID_ROWS, columnIndex, columnIndex);
        DataValidationHelper helper = new XSSFDataValidationHelper((XSSFSheet) dictionarySheet);
        DataValidation validation = helper.createValidation(constraint, regions);
        validation.createErrorBox("error", errorMsg);
        validation.setShowErrorBox(true);
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    /**
     * 计算字典行的引用区间，如 offset=1, rowId=1, colCount=10 → $B$1:$K$1
     */
    private String buildRange(int offset, int rowId, int colCount) {
        char start = (char) ('A' + offset);
        if (colCount <= 25) {
            char end = (char) (start + colCount - 1);
            return "$" + start + "$" + rowId + ":$" + end + "$" + rowId;
        }
        char endPrefix = 'A';
        char endSuffix;
        if ((colCount - 25) / 26 == 0 || colCount == 51) {
            endSuffix = (colCount - 25) % 26 == 0 ? (char) ('A' + 25) : (char) ('A' + (colCount - 25) % 26 - 1);
        } else if ((colCount - 25) % 26 == 0) {
            endSuffix = (char) ('A' + 25);
            endPrefix = (char) (endPrefix + (colCount - 25) / 26 - 1);
        } else {
            endSuffix = (char) ('A' + (colCount - 25) % 26 - 1);
            endPrefix = (char) (endPrefix + (colCount - 25) / 26);
        }
        return "$" + start + "$" + rowId + ":$" + endPrefix + endSuffix + "$" + rowId;
    }

}
