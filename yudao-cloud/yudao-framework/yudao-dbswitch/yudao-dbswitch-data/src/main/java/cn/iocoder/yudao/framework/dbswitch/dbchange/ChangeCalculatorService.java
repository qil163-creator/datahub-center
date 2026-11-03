package cn.iocoder.yudao.framework.dbswitch.dbchange;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.SourceType;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcTypesUtils;
import cn.iocoder.yudao.framework.dbswitch.common.util.TypeConvertUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByDatasourceService;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.constant.Constants;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据变化量计算核心类
 * <p>
 * 职责：在不依赖数据库日志（binlog 等）的前提下，通过对源端（new）与目标端（old）
 * 两个完整读取后按同一规则有界排序的快照做归并比对，计算出 INSERT/UPDATE/DELETE 三类变化记录，
 * 逐条回调 {@link IDatabaseRowHandler}，由调用方（如 MigrationHandler）分类缓存并批量写入目标端。
 * <p>
 * 主键全量比对：{@link #executeCalculate(TaskParamEntity, IDatabaseRowHandler)}，
 * 两端全表经过加密暂存、匹配键唯一性验证后归并比较，不依赖数据库 collation。
 * 增量水位搬数由独立的强类型游标协议执行。
 * 字段值比较策略见 {@link #typeCompare}：按 JDBC 类型分别用字符串/数值/日期方式比较，
 * 无法识别的类型兜底转字节数组逐字节比较
 *
 * @author jrl
 */
@Slf4j
public final class ChangeCalculatorService implements IDatabaseChangeCaculator {

    private final DbSwitchProductRegistry products;

    private final BatchLimits batchLimits;

    private final DbSwitchOptions runtimeOptions;

    /**
     * 是否记录不变化的记录
     */
    private boolean recordIdentical;

    /**
     * 是否进行jdbc数据type检查
     */
    private boolean checkJdbcType;

    /**
     * 批量读取数据的行数大小
     */
    private int queryFetchSize;

    /**
     * 中断检查回调：归并比对循环每次迭代执行（大段无变化记录被跳过回调时也能及时响应中断）
     */
    private Runnable interruptCheck;

    /**
     * 默认构造：不回调无变化的记录，开启 JDBC 类型检查，使用默认 fetchSize
     */
    public ChangeCalculatorService() {
        this(false, true);
    }

    /**
     * @param recordIdentical 是否把“无变化”的记录也回调给处理器
     * @param checkJdbcType   是否校验两端结果集对应字段的 JDBC 类型一致
     */
    public ChangeCalculatorService(boolean recordIdentical, boolean checkJdbcType) {
        this(recordIdentical, checkJdbcType, DbSwitchProductRegistry.discover(), BatchLimits.defaults(),
                DbSwitchOptions.defaults());
    }

    /**
     * 使用给定参数初始化 {@code ChangeCalculatorService}。
     */
    public ChangeCalculatorService(DbSwitchProductRegistry products, BatchLimits limits) {
        this(false, true, products, limits, DbSwitchOptions.defaults());
    }

    /**
     * 使用给定参数初始化 {@code ChangeCalculatorService}。
     */
    public ChangeCalculatorService(DbSwitchProductRegistry products, BatchLimits limits, DbSwitchOptions options) {
        this(false, true, products, limits, options);
    }

    /**
     * 校验构造参数并初始化 {@code ChangeCalculatorService}。
     */
    private ChangeCalculatorService(boolean recordIdentical, boolean checkJdbcType,
            DbSwitchProductRegistry products, BatchLimits limits, DbSwitchOptions options) {
        this.products = java.util.Objects.requireNonNull(products);
        this.batchLimits = java.util.Objects.requireNonNull(limits);
        this.runtimeOptions = java.util.Objects.requireNonNull(options);
        this.recordIdentical = recordIdentical;
        this.checkJdbcType = checkJdbcType;
        this.queryFetchSize = Constants.DEFAULT_FETCH_SIZE;
    }

    /**
     * 创建读取器。
     */
    private IDatabaseOperator createReader(javax.sql.DataSource source,
            cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum product) {
        IDatabaseOperator reader = products.reader(source, product);
        reader.configure(runtimeOptions);
        return reader;
    }

    /**
     * 是否记录无变化的数据。
     */
    @Override
    public boolean isRecordIdentical() {
        return this.recordIdentical;
    }

    /**
     * 设置是否记录无变化的数据。
     */
    @Override
    public void setRecordIdentical(boolean recordOrNot) {
        this.recordIdentical = recordOrNot;
    }

    /**
     * 是否进行Jdbc的数据类型检查。
     */
    @Override
    public boolean isCheckJdbcType() {
        return this.checkJdbcType;
    }

    /**
     * 设置是否进行Jdbc的数据类型检查。
     */
    @Override
    public void setCheckJdbcType(boolean checkOrNot) {
        this.checkJdbcType = checkOrNot;
    }

    /**
     * 获取JDBC驱动批量读取数据的行数大小。
     */
    @Override
    public int getFetchSize() {
        return this.queryFetchSize;
    }

    /**
     * 设置JDBC驱动批量读取数据的行数大小。
     */
    @Override
    public void setFetchSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException(
                    "The difference fetch size must be positive");
        }

        this.queryFetchSize = size;
    }

    /**
     * 设置{@code interrupt}检查。
     */
    @Override
    public void setInterruptCheck(Runnable interruptCheck) {
        this.interruptCheck = interruptCheck;
    }

    /**
     * 执行中断检查回调（未设置时不做任何事）
     */
    private void checkInterrupt() {
        if (interruptCheck != null) {
            interruptCheck.run();
        }
    }

    /**
     * 变化量计算函数
     * <p>
     * 说明 ： old 后缀的为目标段； new 后缀的为来源段；
     * <p>
     * 数据由 new 向 old 方向 同步。
     * <p>
     * 主要流程：
     * 1. 读取两端的字段/主键元数据（SQL 抽取模式按 SQL 解析字段），校验：主键必须存在、
     *    指定的比较字段须在两表中都存在且包含主键、列名映射关系能对上；
     * 2. 计算除主键外参与值比较的字段列表与实际查询的字段列表（应用列名映射）；
     * 3. 对两端各发起一个按主键排序的流式查询，并校验两端结果集列数一致
     *    （checkJdbcType=true 时还校验各列 JDBC 类型一致）；
     * 4. 归并比对（依赖两端结果集都按主键有序）：主键相等时再比较非主键字段判断
     *    “无变化/UPDATE”；旧端主键小说明该记录只存在于目标端，标记 DELETE；
     *    新端主键小说明是源端新增，标记 INSERT；
     * 5. 每条结果回调 handler.handle()（recordIdentical=false 时跳过无变化记录），
     *    全部处理完回调 handler.destroy() 让调用方刷出剩余缓存
     *
     * @param task    任务描述实体对象
     * @param handler 计算结果回调处理器
     */
    @Override
    public void executeCalculate(@NonNull TaskParamEntity task,
            @NonNull IDatabaseRowHandler handler) {
        if (log.isDebugEnabled()) {
            log.debug("###### Begin execute calculate table primary keys CDC data now");
        }

        Map<String, String> columnsMap = task.getColumnsMap();
        boolean useOwnFieldsColumns = !CollectionUtils.isEmpty(task.getFieldColumns());

        // 检查新旧两张表的主键字段与比较字段
        IMetaDataByDatasourceService oldMd = new MetaDataByDataSourceServiceImpl(task.getOldDataSource(),
                task.getOldProductType(), products);
        IMetaDataByDatasourceService newMd = new MetaDataByDataSourceServiceImpl(task.getNewDataSource(),
                task.getNewProductType(), products);
        //List<String> fieldsPrimaryKeyOld = oldMd.queryTablePrimaryKeys(task.getOldSchemaName(), task.getOldTableName());
        IDatabaseOperator oldQuery = createReader(task.getOldDataSource(), task.getOldProductType());
        IDatabaseOperator newQuery = createReader(task.getNewDataSource(), task.getNewProductType());
        List<ColumnDescription> oldNative = oldQuery.nativeMigrationColumns(task.getOldSchemaName(),
                task.getOldTableName());
        List<String> fieldsAllColumnOld = oldNative.isEmpty()
                ? oldMd.queryTableColumnName(task.getOldSchemaName(), task.getOldTableName())
                : oldNative.stream().map(ColumnDescription::getFieldName).toList();
        List<String> fieldsPrimaryKeyNew;
        List<String> fieldsAllColumnNew;
        if (SourceType.TABLE.getCode().equals(task.getSourceType())) {
            fieldsPrimaryKeyNew = CollectionUtils.isEmpty(task.getSourcePrimaryKeys()) ? newMd.queryTablePrimaryKeys(
                    task.getNewSchemaName(), task.getNewTableName()) : task.getSourcePrimaryKeys();
            List<ColumnDescription> newNative = newQuery.nativeMigrationColumns(task.getNewSchemaName(),
                    task.getNewTableName());
            fieldsAllColumnNew = newNative.isEmpty()
                    ? newMd.queryTableColumnName(task.getNewSchemaName(), task.getNewTableName())
                    : newNative.stream().map(ColumnDescription::getFieldName).toList();
        } else {
            fieldsPrimaryKeyNew = task.getSourcePrimaryKeys();
            fieldsAllColumnNew = newMd.querySqlColumnMeta(task.getSourceSql()).stream()
                    .map(ColumnDescription::getFieldName).collect(Collectors.toList());
        }

        if (fieldsPrimaryKeyNew == null || fieldsPrimaryKeyNew.isEmpty()
                || new java.util.HashSet<>(fieldsPrimaryKeyNew).size() != fieldsPrimaryKeyNew.size()) {
            throw new IllegalArgumentException("Difference matching keys must be explicit and distinct");
        }
        List<String> fieldsMappedPrimaryKeyNew = fieldsPrimaryKeyNew.stream()
                .map(s -> columnsMap.getOrDefault(s, s))
                .collect(Collectors.toList());
        List<String> fieldsMappedAllColumnNew = fieldsAllColumnNew.stream()
                .map(s -> columnsMap.getOrDefault(s, s)).toList();

        // 显式匹配键可能与源端原生键不同；下方会检查两个完整输入快照
        // 中的重复键和空键；所选目标同步器负责表模型
        // 和原生约束验证，警告不能作为唯一性证据。

        if (useOwnFieldsColumns) {
            // 如果自己配置了字段列表，判断子集关系
            List<String> mappedFieldColumns = task.getFieldColumns().stream()
                    .map(s -> columnsMap.getOrDefault(s, s)).toList();
            if (!fieldsAllColumnNew.containsAll(task.getFieldColumns())
                    || !fieldsAllColumnOld.containsAll(mappedFieldColumns)) {
                throw new RuntimeException("指定的字段列不完全在两个表中存在");
            }
            boolean same = (mappedFieldColumns.containsAll(fieldsMappedPrimaryKeyNew)
                    && task.getFieldColumns().containsAll(fieldsPrimaryKeyNew));
            if (!same) {
                throw new RuntimeException("提供的比较字段中未包含主键");
            }
            same = (fieldsAllColumnOld.containsAll(mappedFieldColumns)
                    && fieldsAllColumnNew.containsAll(task.getFieldColumns()));
            if (!same) {
                throw new RuntimeException("提供的比较字段中存在表中不存在(映射关系对不上)的字段");
            }
        } else {
            boolean same = (fieldsMappedAllColumnNew.containsAll(fieldsMappedPrimaryKeyNew)
                    && fieldsAllColumnOld.containsAll(fieldsMappedAllColumnNew));
            if (!same) {
                throw new RuntimeException("两个表的字段映射关系不匹配");
            }
        }

        // 计算除主键外的比较字段
        List<String> fieldsOfCompareValue = new ArrayList<>();
        if (useOwnFieldsColumns) {
            fieldsOfCompareValue.addAll(task.getFieldColumns());
        } else {
            fieldsOfCompareValue.addAll(fieldsAllColumnNew);
        }
        fieldsOfCompareValue.removeAll(fieldsPrimaryKeyNew);

        // 构造查询列字段
        List<String> queryFieldColumn;
        List<String> mappedQueryFieldColumn;
        if (useOwnFieldsColumns) {
            queryFieldColumn = task.getFieldColumns();
        } else {
            queryFieldColumn = fieldsAllColumnNew;
        }
        mappedQueryFieldColumn = queryFieldColumn.stream()
                .map(s -> columnsMap.getOrDefault(s, s))
                .collect(Collectors.toList());
        if (new java.util.HashSet<>(mappedQueryFieldColumn).size() != mappedQueryFieldColumn.size()
                || mappedQueryFieldColumn.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("Difference fields must map to distinct physical columns");
        }
        List<String> nativeTargetKeys = oldMd.queryTablePrimaryKeys(task.getOldSchemaName(), task.getOldTableName());
        if (!CollectionUtils.isEmpty(nativeTargetKeys)) {
            if (!new java.util.HashSet<>(nativeTargetKeys).equals(new java.util.HashSet<>(fieldsMappedPrimaryKeyNew))) {
                throw new IllegalArgumentException(
                        "Difference matching keys must match the complete native target key");
            }
        } else {
            var synchronizer = products.synchronizer(task.getOldDataSource(), task.getOldProductType());
            if (!synchronizer.supportsUnconstrainedMatchKeys()) {
                throw new IllegalArgumentException("Target matching keys require a native uniqueness constraint");
            }
            synchronizer.configure(runtimeOptions);
            synchronizer.configureBatchLimits(batchLimits);
            synchronizer.prepare(task.getOldSchemaName(), task.getOldTableName(), mappedQueryFieldColumn,
                    fieldsMappedPrimaryKeyNew);
        }

        StatementResultSet rsold = null;
        StatementResultSet rsnew = null;

        try {
            // 提取新旧两表数据的结果集(按主键排序后的)
            oldQuery.setFetchSize(this.queryFetchSize);
            newQuery.setFetchSize(this.queryFetchSize);

            if (log.isDebugEnabled()) {
                log.debug("###### Query data from two table now");
            }

            rsold = oldQuery
                    .queryTableData(task.getOldSchemaName(), task.getOldTableName(),
                            mappedQueryFieldColumn);
            if (SourceType.TABLE.getCode().equals(task.getSourceType())) {
                rsnew = newQuery
                        .queryTableData(task.getNewSchemaName(), task.getNewTableName(),
                                queryFieldColumn);
            } else {
                //sql
                rsnew = newQuery.queryTableData(task.getSourceSql(), queryFieldColumn, List.of());
            }

            ResultSetMetaData metaData = rsnew.getResultset().getMetaData();

            if (log.isDebugEnabled()) {
                log.debug("###### Check data validate now");
            }

            // 检查结果集源信息是否一直
            int oldcnt = rsold.getResultset().getMetaData().getColumnCount();
            int newcnt = rsnew.getResultset().getMetaData().getColumnCount();
            if (oldcnt != newcnt) {
                throw new RuntimeException(String.format("两个表的字段总个数不相等，即：%d!=%d", oldcnt, newcnt));
            } else {
                // 注意：JDBC 列下标从 1 开始且包含 columnCount，此处必须用 <= 才能校验到最后一列
                for (int k = 1; k <= metaData.getColumnCount(); ++k) {
                    String key1 = rsnew.getResultset().getMetaData().getColumnLabel(k);
                    if (null == key1) {
                        key1 = rsnew.getResultset().getMetaData().getColumnName(k);
                    }

                    String key2 = rsold.getResultset().getMetaData().getColumnLabel(k);
                    if (null == key2) {
                        key2 = rsold.getResultset().getMetaData().getColumnName(k);
                    }

                    if (checkJdbcType) {
                        int type1 = rsold.getResultset().getMetaData().getColumnType(k);
                        int type2 = rsnew.getResultset().getMetaData().getColumnType(k);
                        if (type1 != type2) {
                            throw new RuntimeException(String.format("字段 [name=%s -> %s] 的数据类型不同，因 %s!=%s !",
                                    key1, key2,
                                    JdbcTypesUtils.resolveTypeName(type1), JdbcTypesUtils.resolveTypeName(type2)));
                        }
                    }

                }
            }

            // 计算主键字段序列在结果集中的索引号
            int[] keyNumbers = new int[fieldsPrimaryKeyNew.size()];
            for (int i = 0; i < keyNumbers.length; ++i) {
                String fn = fieldsPrimaryKeyNew.get(i);
                keyNumbers[i] = getIndexOfField(fn, metaData);
                if (keyNumbers[i] < 0) {
                    throw new RuntimeException(
                            String.format("主键字段[%s]在查询结果集中不存在，请检查主键与字段映射配置", fn));
                }
            }

            // 计算比较(非主键)字段序列在结果集中的索引号
            int[] valNumbers = new int[fieldsOfCompareValue.size()];
            for (int i = 0; i < valNumbers.length; ++i) {
                String fn = fieldsOfCompareValue.get(i);
                valNumbers[i] = getIndexOfField(fn, metaData);
                if (valNumbers[i] < 0) {
                    throw new RuntimeException(
                            String.format("比较字段[%s]在查询结果集中不存在，请检查字段映射配置", fn));
                }
            }

            // 初始化计算结果数据字段列信息
            List<String> targetColumns = new ArrayList<>();
            for (int k = 1; k <= metaData.getColumnCount(); ++k) {
                String key = metaData.getColumnLabel(k);
                if (null == key) {
                    key = metaData.getColumnName(k);
                }
                targetColumns.add(columnsMap.getOrDefault(key, key));
            }

            if (log.isDebugEnabled()) {
                log.debug("###### Enter CDC calculate now");
            }

            // 进入核心比较计算算法区域
            // 归并比对：one 为目标端（旧数据）当前行，two 为源端（新数据）当前行，
            // 两个结果集都已按主键排序，比较后只推进较小的一侧，类似归并排序的合并阶段
            int[] columnTypes = new int[newcnt];
            for (int i = 0; i < newcnt; i++) {
                columnTypes[i] = metaData.getColumnType(i + 1);
            }
            int[] keyTypes = new int[keyNumbers.length];
            for (int i = 0; i < keyNumbers.length; i++) {
                keyTypes[i] = columnTypes[keyNumbers[i]];
            }
            java.util.Comparator<Object[]> keyComparator = (left, right) -> {
                for (int i = 0; i < keyNumbers.length; i++) {
                    Object oneKey = left[keyNumbers[i]], twoKey = right[keyNumbers[i]];
                    requireComparableKey(keyTypes[i], oneKey);
                    requireComparableKey(keyTypes[i], twoKey);
                    int order = oneKey instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue leftValue
                            && twoKey instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue rightValue
                            ? leftValue.compareMatchingKey(rightValue) : typeCompare(keyTypes[i], oneKey, twoKey);
                    if (order != 0) {
                        return order;
                    }
                }
                return 0;
            };
            final ResultSet oldRows = rsold.getResultset(), newRows = rsnew.getResultset();
            // 数据库排序规则不决定 Java 比较器行为；物化两个快照并使用
            // 同一个比较器排序，写入前验证每个键，包括转换后的键。
            try (var sort = new DiffRowSort(runtimeOptions.diffSort(), newcnt, batchLimits.maxBatchBytes(),
                    this::checkInterrupt)) {
                var oldRun = sort.sort(() -> getRowData(oldRows), keyComparator, keyNumbers);
                var newRun = sort.sort(() -> readNewRow(newRows, task, queryFieldColumn), keyComparator, keyNumbers);
                // 所有值和 JDBC 类型现已脱离读取资源；写入前释放读取连接，
                // 即使目标连接池只允许一个物理连接也遵循此规则。
                rsold.close();
                rsold = null;
                rsnew.close();
                rsnew = null;
                try (var sortedOld = sort.rows(oldRun); var sortedNew = sort.rows(newRun)) {
                    RecordChangeTypeEnum flagField;
                    Object[] outputRow;
                    Object[] one = sortedOld.next();
                    Object[] two = sortedNew.next();
                    while (true) {
                        checkInterrupt();
                        if (one == null && two == null) {
                            // 两端都读完，比对结束
                            break;
                        } else if (one == null && two != null) {
                            // 目标端已读完：源端剩余记录全部为新增
                            flagField = RecordChangeTypeEnum.VALUE_INSERT;
                            outputRow = two;
                            two = sortedNew.next();
                        } else if (one != null && two == null) {
                            // 源端已读完：目标端剩余记录在源端已不存在，标记删除
                            flagField = RecordChangeTypeEnum.VALUE_DELETED;
                            outputRow = one;
                            one = sortedOld.next();
                        } else {
                            // 两端都有数据：先按主键比较
                            int compare = keyComparator.compare(one, two);
                            if (0 == compare) {
                                // 主键相同：再比较非主键字段，判断记录内容是否发生变化
                                int compareValues = this.compare(one, two, valNumbers, columnTypes);
                                if (compareValues == 0) {
                                    flagField = RecordChangeTypeEnum.VALUE_IDENTICAL;
                                    outputRow = one;
                                } else {
                                    flagField = RecordChangeTypeEnum.VALUE_CHANGED;
                                    outputRow = two;
                                }

                                // 主键相同时两端同时向后推进一行
                                one = sortedOld.next();
                                two = sortedNew.next();
                            } else {
                                if (compare < 0) {
                                    // 目标端主键较小：该主键只存在于目标端，属于删除，目标端推进一行
                                    flagField = RecordChangeTypeEnum.VALUE_DELETED;
                                    outputRow = one;
                                    one = sortedOld.next();
                                } else {
                                    // 源端主键较小：该主键只存在于源端，属于新增，源端推进一行
                                    flagField = RecordChangeTypeEnum.VALUE_INSERT;
                                    outputRow = two;
                                    two = sortedNew.next();
                                }
                            }
                        }

                        // 未开启记录无变化数据时，跳过内容相同的记录
                        if (!this.recordIdentical && RecordChangeTypeEnum.VALUE_IDENTICAL == flagField) {
                            continue;
                        }

                        // 这里对计算的单条记录结果进行处理
                        handler.handle(Collections.unmodifiableList(targetColumns), outputRow, flagField);
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("###### Calculate CDC Over now");
                    }

                    // 结束返回前的回调
                    handler.destroy(Collections.unmodifiableList(targetColumns));
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (null != rsold) {
                rsold.close();
            }
            if (null != rsnew) {
                rsnew.close();
            }
        }

    }

    /**
     * 检查键值是否处于可稳定比较的受支持值域。
     */
    private static void requireComparableKey(int jdbcType, Object value) {
        boolean supported = value != null && (
                (JdbcTypesUtils.isString(jdbcType) && value instanceof String)
                        || (JdbcTypesUtils.isNumeric(jdbcType) && value instanceof Number)
                        || (JdbcTypesUtils.isInteger(jdbcType) && value instanceof Number)
                        || (JdbcTypesUtils.isBoolean(jdbcType) && (value instanceof Boolean || value instanceof Number))
                        || (JdbcTypesUtils.isDateTime(jdbcType) && (value instanceof java.sql.Timestamp
                        || value instanceof java.sql.Date || value instanceof java.sql.Time || value instanceof LocalDate
                        || value instanceof LocalTime || value instanceof LocalDateTime || value instanceof OffsetDateTime
                        || value instanceof java.time.OffsetTime || value instanceof java.time.Instant || value instanceof java.time.ZonedDateTime))
                        || value instanceof byte[] || value instanceof java.util.UUID
                        || value instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue nativeValue && nativeValue.hasMatchingKey());
        if (!supported) {
            throw new IllegalArgumentException("Unsupported or null difference matching key type");
        }
    }

    /**
     * 获取字段在结果集中的索引号（0 基下标，未找到返回 -1）
     *
     * @param key      字段名
     * @param metaData 结果集的元信息
     * @return 字段的索引号（0 基），未找到返回 -1
     * @throws SQLException 读取元信息失败时抛出
     */
    private int getIndexOfField(String key, ResultSetMetaData metaData) throws SQLException {
        for (int k = 1; k <= metaData.getColumnCount(); ++k) {
            String fieldName = metaData.getColumnLabel(k);
            if (null == fieldName) {
                fieldName = metaData.getColumnName(k);
            }

            if (fieldName.equals(key)) {
                return k - 1;
            }
        }

        return -1;
    }

    /**
     * 记录比较
     *
     * @param obj1     记录1
     * @param obj2     记录2
     * @param fieldnrs 待比较的字段索引号
     * @param metaData 记录集的元信息
     * @return 比较的结果：0，-1，1
     * @throws SQLException
     */
    private int compare(Object[] obj1, Object[] obj2, int[] fieldnrs, int[] columnTypes) {
        if (obj1.length != obj2.length) {
            throw new RuntimeException("Invalid compare object list !");
        }

        for (int fieldnr : fieldnrs) {
            int jdbcType = columnTypes[fieldnr];
            Object o1 = obj1[fieldnr];
            Object o2 = obj2[fieldnr];

            int cmp = typeCompare(jdbcType, o1, o2);
            if (cmp != 0) {
                return cmp;
            }
        }

        return 0;
    }

    /**
     * 字段值对象比较，将对象转换为字节数组来比较实现
     * <p>
     * 包内可见以便单元测试直接验证各类型分支的比较行为
     *
     * @param type 字段的JDBC数据类型
     * @param o1   对象1
     * @param o2   对象2
     * @return 0为相等，-1为小于，1为大于
     */
    int typeCompare(int type, Object o1, Object o2) {
        // null 值排序规则：null 视为最小，两个都为 null 视为相等
        boolean n1 = (o1 == null);
        boolean n2 = (o2 == null);
        if (n1 && !n2) {
            return -1;
        }
        if (!n1 && n2) {
            return 1;
        }
        if (n1 && n2) {
            return 0;
        }

        /**
         * <p>
         * 这里要比较的两个对象o1与o2可能类型不同，但值相同，例如： Integer o1=12，Long o2=12;
         * </p>
         * <p>
         * 但是这种不属于同一类的比较情况不应出现: String o1="12", Integer o2=12;
         * </p>
         */
        if (o1 == cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE
                || o2 == cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE) {
            return o1 == o2 ? 0 : o1 == cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE ? -1 : 1;
        }
        if (o1 instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue
                || o2 instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue) {
            if (o1 instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue left
                    && o2 instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue right) {
                return left.compareValue(right);
            }
            throw new IllegalArgumentException("Native and relational values require an explicit conversion plan");
        }
        if (o1 instanceof java.util.UUID left && o2 instanceof java.util.UUID right) {
            return left.compareTo(right);
        } else if (o1 instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue left
                && o2 instanceof cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue right) {
            if (left.baseJdbcType() != right.baseJdbcType()) {
                throw new IllegalArgumentException("Difference array element types do not match");
            }
            int size = Math.min(left.elements().size(), right.elements().size());
            for (int i = 0; i < size; i++) {
                int order = typeCompare(left.baseJdbcType(), left.elements().get(i), right.elements().get(i));
                if (order != 0) {
                    return order;
                }
            }
            return Integer.compare(left.elements().size(), right.elements().size());
        } else if (JdbcTypesUtils.isString(type)) {
            String s1 = TypeConvertUtils.castToString(o1);
            String s2 = TypeConvertUtils.castToString(o2);
            return s1.compareTo(s2);
        } else if (JdbcTypesUtils.isNumeric(type) && o1 instanceof Number
                && o2 instanceof Number) {
            // 使用 BigDecimal 精确比较，避免 double 对大整数/高精度小数的精度丢失导致变化漏检
            return toBigDecimal((Number) o1).compareTo(toBigDecimal((Number) o2));
        } else if ((JdbcTypesUtils.isInteger(type) || JdbcTypesUtils.isBoolean(type)) &&
                (o1 instanceof Number && o2 instanceof Number ||
                        o1 instanceof Number && o2 instanceof Boolean ||
                        o1 instanceof Boolean && o2 instanceof Number ||
                        o1 instanceof Boolean && o2 instanceof Boolean)) {
            Number s1 = o1 instanceof Boolean ? (Boolean) o1 ? 1 : 0 : (Number) o1;
            Number s2 = o2 instanceof Boolean ? (Boolean) o2 ? 1 : 0 : (Number) o2;
            // BIGINT 驱动可能返回 BigInteger（如 unsigned BIGINT），不能先截断为 long。
            return toBigDecimal(s1).compareTo(toBigDecimal(s2));
        } else if (JdbcTypesUtils.isDateTime(type)) {
            if (type == java.sql.Types.DATE) {
                return civilDate(o1).compareTo(civilDate(o2));
            }
            if (type == java.sql.Types.TIME && isLocalTimeValue(o1) && isLocalTimeValue(o2)) {
                return toLocalTime(o1).compareTo(toLocalTime(o2));
            }
            if (type == java.sql.Types.TIME && o1 instanceof String left && o2 instanceof String right) {
                return Long.compare(
                        cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.timeDurationMicros(left),
                        cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.timeDurationMicros(right));
            }
            if (type == java.sql.Types.TIMESTAMP && isCivilTimestamp(o1) && isCivilTimestamp(o2)) {
                return civilTimestamp(o1).compareTo(civilTimestamp(o2));
            }
            if (type == java.sql.Types.TIME_WITH_TIMEZONE && o1 instanceof java.time.OffsetTime left
                    && o2 instanceof java.time.OffsetTime right) {
                return left.atDate(LocalDate.ofEpochDay(0)).toInstant()
                        .compareTo(right.atDate(LocalDate.ofEpochDay(0)).toInstant());
            }
            if (type == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
                return instantTimestamp(o1).compareTo(instantTimestamp(o2));
            }
            throw new IllegalArgumentException(
                    "Difference temporal values require an explicit compatible type mapping");
        } else {
            // 无法转换意味着无法证明相等，必须中止本轮比对，避免漏报更新或推进检查点。
            try {
                return compareTo(
                        TypeConvertUtils.castToByteArray(o1),
                        TypeConvertUtils.castToByteArray(o2)
                );
            } catch (Exception e) {
                // 不附加对象值或驱动异常原文，避免错误链泄露源字段内容。
                throw new IllegalArgumentException("Unable to compare values for JDBC type " + type
                        + " (" + o1.getClass().getName() + ", " + o2.getClass().getName() + ")");
            }
        }
    }

    /**
     * 判断本地时间值。
     */
    private boolean isLocalTimeValue(Object value) {
        return value instanceof LocalTime || value instanceof java.sql.Time;
    }

    /**
     * 转换为本地时间。
     */
    private LocalTime toLocalTime(Object value) {
        if (value instanceof LocalTime localTime) {
            return localTime;
        }
        java.sql.Time sqlTime = (java.sql.Time) value;
        // Time.toLocalTime() 仅保留整秒；一些驱动仍在 getTime() 中携带毫秒。
        int nanos = (int) Math.floorMod(sqlTime.getTime(), 1000L) * 1_000_000;
        return sqlTime.toLocalTime().withNano(nanos);
    }

    /**
     * 按当前协议解析不含时区的日历日期。
     */
    private static LocalDate civilDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        throw new IllegalArgumentException("Difference DATE requires a civil date");
    }

    /**
     * 判断当前时间戳是否采用不含时区的本地日期时间语义。
     */
    private static boolean isCivilTimestamp(Object value) {
        return value instanceof LocalDateTime || value instanceof java.sql.Timestamp;
    }

    /**
     * 按当前协议解析不含时区的日期时间值。
     */
    private static LocalDateTime civilTimestamp(Object value) {
        return value instanceof LocalDateTime time ? time : ((java.sql.Timestamp) value).toLocalDateTime();
    }

    /**
     * 按当前协议解析表示绝对时间点的时间戳。
     */
    private static java.time.Instant instantTimestamp(Object value) {
        if (value instanceof java.time.Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime time) {
            return time.toInstant();
        }
        if (value instanceof java.time.ZonedDateTime time) {
            return time.toInstant();
        }
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant();
        }
        throw new IllegalArgumentException("Difference TIMESTAMP WITH TIME ZONE requires an instant");
    }

    /**
     * 数值统一转换为 BigDecimal 做精确比较
     */
    private BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        }
        if (number instanceof BigInteger) {
            return new BigDecimal((BigInteger) number);
        }
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Float || number instanceof Double) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(number.toString());
    }

    /**
     * 字节数组的比较
     *
     * @param s1 字节数组1
     * @param s2 字节数组2
     * @return 0为相等，-1为小于，1为大于
     */
    public int compareTo(byte[] s1, byte[] s2) {
        int len1 = s1.length;
        int len2 = s2.length;
        int lim = Math.min(len1, len2);
        byte[] v1 = s1;
        byte[] v2 = s2;

        int k = 0;
        while (k < lim) {
            byte c1 = v1[k];
            byte c2 = v2[k];
            if (c1 != c2) {
                return c1 - c2;
            }
            k++;
        }
        return len1 - len2;
    }

    /**
     * 读取源端（新数据）一行并应用行级值转换钩子（脱敏/值映射）；
     * 转换后的值同时用于比对与写入，保证比对结果与最终落库数据一致
     */
    private Object[] readNewRow(ResultSet rs, TaskParamEntity task, List<String> queryFieldColumn)
            throws SQLException {
        Object[] row = getRowData(rs);
        if (row == null || task.getTransformer() == null) {
            return row;
        }
        String tableName = SourceType.SQL.getCode().equals(task.getSourceType())
                ? task.getSourceSql() : task.getNewTableName();
        Object[] transformed = task.getTransformer().transform(
                task.getNewSchemaName(), tableName, queryFieldColumn, row);
        if (transformed == null || transformed.length != row.length) {
            throw new IllegalArgumentException("Difference transformer must return exactly one complete row");
        }
        return BatchValueConverter.convert(List.<Object[]>of(transformed), row.length, batchLimits).getFirst();
    }

    /**
     * 从结果集中取出一条记录
     *
     * @param rs 记录集
     * @return 一条记录，到记录结尾时返回null
     * @throws SQLException
     */
    private Object[] getRowData(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        Object[] rowData = null;

        if (rs.next()) {
            rowData = new Object[metaData.getColumnCount()];
            for (int j = 1; j <= metaData.getColumnCount(); ++j) {
                rowData[j - 1] = rs.getObject(j);
            }
        }

        return rowData == null ? null
                : BatchValueConverter.convert(List.<Object[]>of(rowData), rowData.length, batchLimits).getFirst();
    }

}
