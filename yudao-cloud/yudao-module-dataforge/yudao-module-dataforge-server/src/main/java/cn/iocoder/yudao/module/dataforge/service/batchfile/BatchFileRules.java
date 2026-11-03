package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileUploadDO;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 批量入库领域约束，解析和数据库技术机制委托公共组件。 */
public final class BatchFileRules {

    public static final int MAX_FILES = 20, MAX_ROWS = 100000, MAX_COLUMNS = 256;

    public static final long MAX_BYTES = 20L * 1024 * 1024, MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    public static final List<String> TYPES = List.of("string", "bigint", "int", "decimal", "double", "date", "datetime",
            "boolean");

    /**
     * 限制批次文件规则工具的实例化，统一通过静态能力使用。
     */
    private BatchFileRules() {
    }

    /**
     * 记录批次文件规则审计信息。
     *
     * @param row 当前行数据
     * @param actor 操作人编号
     */
    static void audit(cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO row, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("审计主体不能为空");
        }
        if (row.getCreator() == null) {
            row.setCreator(actor);
        }
        if (row.getCreateTime() == null) {
            row.setCreateTime(LocalDateTime.now());
        }
        if (row.getDeleted() == null) {
            row.setDeleted(false);
        }
        row.setUpdater(actor);
        row.setUpdateTime(LocalDateTime.now());
    }

    /**
     * 读取批次文件规则。
     *
     * @param <T> 处理数据的类型
     * @param json JSON 内容
     * @param type 类型标识
     * @return 批次文件规则
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return JsonUtils.getObjectMapper().readValue(json, type);
        } catch (Exception ex) {
            throw exception(BATCH_FILE_CONFIG_INVALID, "持久配置无法读取");
        }
    }

    /**
     * 获取批次文件规则的JSON。
     *
     * @param value 待处理的值
     * @return 批次文件规则的JSON文本
     */
    public static String json(Object value) {
        return JsonUtils.toJsonString(value);
    }

    /**
     * 获取批次文件规则的扩展。
     *
     * @param name 名称
     * @return 批次文件规则的扩展文本
     */
    public static String extension(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.chars().anyMatch(Character::isISOControl)) {
            throw exception(BATCH_FILE_CONFIG_INVALID, "文件名称不合法");
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("csv", "xls", "xlsx").contains(ext)) {
            throw exception(BATCH_FILE_CONFIG_INVALID, "仅支持 CSV、XLS、XLSX");
        }
        return ext;
    }

    /**
     * 读取当前请求使用的请求头映射。
     *
     * @param headers 请求头映射
     * @return 当前请求使用的请求头映射结果集合
     */
    public static List<Field> headers(List<String> headers) {
        if (headers.isEmpty() || headers.size() > MAX_COLUMNS) {
            throw exception(BATCH_FILE_CONFIG_INVALID, "文件列数不合法");
        }
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            Field f = new Field();
            f.setSourceIndex(i);
            f.setName(headers.get(i));
            f.setType("string");
            fields.add(f);
        }
        validateFields(fields, headers.size());
        return fields;
    }

    /**
     * 校验字段。
     *
     * @param fields 字段定义
     * @param originalCount 原始数量
     */
    public static void validateFields(List<Field> fields, int originalCount) {
        if (fields == null || fields.isEmpty() || fields.size() > MAX_COLUMNS) {
            invalid("至少保留一个字段");
        }
        Set<String> names = new HashSet<>();
        Set<Integer> indices = new HashSet<>();
        for (Field field : fields) {
            if (field == null || field.getName() == null || field.getName().isBlank() || field.getName().length() > 64
                    || !field.getName().equals(field.getName().strip()) || field.getName().chars()
                    .anyMatch(Character::isISOControl)
                    || !names.add(field.getName().toLowerCase(Locale.ROOT))) {
                invalid("字段名称不能为空、重复或包含首尾空白及控制字符");
            }
            if (!TYPES.contains(field.getType())) {
                invalid("字段类型不支持");
            }
            if (field.getSourceIndex() == null || field.getSourceIndex() < 0 || field.getSourceIndex() >= originalCount
                    || !indices.add(field.getSourceIndex())) {
                invalid("字段来源索引不合法");
            }
        }
    }

    /**
     * 校验批次文件规则输入及状态。
     *
     * @param request 请求参数
     * @param uploads 上传
     * @param actor 操作人编号
     * @param taskId 任务编号
     */
    public static void validate(Save request, Map<Long, BatchFileUploadDO> uploads, Long actor, Long taskId) {
        if (actor == null || request.getFiles() == null || request.getFiles().isEmpty() || request.getFiles()
                .size() > MAX_FILES) {
            invalid("文件数量必须为 1 至 20");
        }
        Set<Long> ids = new HashSet<>();
        Map<Long, List<Field>> fields = new HashMap<>();
        long total = 0;
        for (FileSelection file : request.getFiles()) {
            if (!ids.add(file.getId())) {
                invalid("文件不能重复");
            }
            BatchFileUploadDO upload = uploads.get(file.getId());
            if (upload == null || !"READY".equals(upload.getState()) || (upload.getTaskId() == null
                    ? !Objects.equals(upload.getOwnerId(), actor) || upload.getExpiresAt().isBefore(LocalDateTime.now())
                    : !Objects.equals(taskId, upload.getTaskId()))) {
                throw exception(BATCH_FILE_UPLOAD_INVALID);
            }
            total += upload.getSize();
            Field[] original = read(upload.getFieldsJson(), Field[].class);
            validateFields(file.getFields(), original.length);
            fields.put(file.getId(), file.getFields());
        }
        if (total > MAX_TOTAL_BYTES) {
            invalid("任务文件总大小不能超过 100 MB");
        }
        if (request.getTargets() == null || request.getTargets().isEmpty() || request.getTargets().size() > MAX_FILES) {
            invalid("目标表配置不能为空");
        }
        Set<String> names = new HashSet<>();
        Set<Long> assigned = new HashSet<>();
        for (Target target : request.getTargets()) {
            if (target.getName() == null || !target.getName().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                    || !names.add(target.getName().toLowerCase(Locale.ROOT))) {
                invalid("目标表名称不合法或重复");
            }
            if (target.getFileIds() == null || target.getFileIds().isEmpty()) {
                invalid("目标表必须包含文件");
            }
            List<Field> first = null;
            for (Long id : target.getFileIds()) {
                if (!ids.contains(id) || !assigned.add(id)) {
                    invalid("每个文件必须且只能分配到一个目标表");
                }
                List<Field> current = fields.get(id);
                if (first != null && !sameStructure(first, current)) {
                    invalid("合并文件的字段名称、类型和顺序必须完全相同");
                }
                first = current;
            }
        }
        if (!assigned.equals(ids)) {
            invalid("存在未分配目标表的文件");
        }
    }

    /**
     * 判断是否一致结构条件。
     *
     * @param a 首个值
     * @param b 第二个值
     * @return 判断结果
     */
    public static boolean sameStructure(List<Field> a, List<Field> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Objects.equals(a.get(i).getName(), b.get(i).getName()) || !Objects.equals(a.get(i).getType(),
                    b.get(i).getType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 提取批次文件规则字段值。
     *
     * @param text 待处理文本
     * @param type 类型标识
     * @return 批次文件规则
     */
    public static Object value(String text, String type) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return switch (type) {
            case "string" -> text;
            case "bigint" -> Long.valueOf(text.strip());
            case "int" -> Integer.valueOf(text.strip());
            case "decimal" -> {
                BigDecimal decimal = new BigDecimal(text.strip());
                if (Math.max(0, decimal.scale()) > 18 || decimal.precision() - decimal.scale() > 20) {
                    throw new IllegalArgumentException("decimal(38,18)");
                }
                yield decimal;
            }
            case "double" -> {
                double number = Double.parseDouble(text.strip());
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("finite");
                }
                yield number;
            }
            case "date" -> {
                LocalDate date = LocalDate.parse(text.strip());
                if (date.getYear() < 1000 || date.getYear() > 9999) {
                    throw new IllegalArgumentException("date range");
                }
                yield java.sql.Date.valueOf(date);
            }
            case "datetime" -> {
                LocalDateTime date = LocalDateTime.parse(text.strip().replace(' ', 'T'));
                if (date.getYear() < 1000 || date.getYear() > 9999 || date.getNano() % 1000 != 0) {
                    throw new IllegalArgumentException("datetime precision");
                }
                yield java.sql.Timestamp.valueOf(date);
            }
            case "boolean" -> {
                if ("true".equalsIgnoreCase(text.strip()) || "1".equals(text.strip())) {
                    yield true;
                }
                if ("false".equalsIgnoreCase(text.strip()) || "0".equals(text.strip())) {
                    yield false;
                }
                throw new IllegalArgumentException("boolean");
            }
            default -> throw new IllegalArgumentException("type");
        };
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @param reason 操作原因
     */
    private static void invalid(String reason) {
        throw exception(BATCH_FILE_CONFIG_INVALID, reason);
    }
}

