package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;

import javax.sql.DataSource;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.List;

/**
 * MySQL协议兼容的OLAP数据库写入基础实现（Doris/StarRocks）。
 * <p>
 * 写入策略：优先使用 Stream Load（HTTP）批量装载。
 * 仅在初始化阶段尚未发出批次时允许选择 JDBC；
 * 发送后响应丢失或无法确认结果时必须停止，不得回退重写同一批数据。
 * 可通过 yudao.dbswitch.migration.olap-stream-load-enabled=false 全局关闭
 */
@Slf4j
public abstract class MysqlCompatibleOlapWriterImpl extends AbstractDatabaseWriter implements IDatabaseWriter {

    /** Stream Load 辅助器，null 表示不可用（未启用/初始化失败），走 JDBC 写入 */
    private OlapStreamLoadHelper streamLoadHelper;

    /**
     * 初始化 {@code MysqlCompatibleOlapWriterImpl}及其父类状态。
     */
    public MysqlCompatibleOlapWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schemaName, String tableName,
            List<String> fieldNames) {
        if (CollectionUtils.isEmpty(fieldNames)) {
            return String.format("SELECT *  FROM `%s`.`%s`  WHERE 1=2", schemaName, tableName);
        } else {
            return String.format("SELECT `%s`  FROM `%s`.`%s`  WHERE 1=2",
                    StringUtils.join(fieldNames, "`,`"), schemaName, tableName);
        }
    }

    /**
     * 批量写入预处理。
     */
    @Override
    public void prepareWrite(String schemaName, String tableName, List<String> fieldNames) {
        if (runtimeOptions.olapStreamLoadEnabled() && !streamLoadVerified()) {
            unsupported(getDatabaseProductName() + " WRITE_COPY is not verified");
        }
        super.prepareWrite(schemaName, tableName, fieldNames);
        if (runtimeOptions.olapStreamLoadEnabled()) {
            this.streamLoadHelper = OlapStreamLoadHelper.tryCreate(
                    dataSource, schemaName, tableName, getDatabaseProductName(), runtimeOptions.queryTimeoutSeconds());
        } else {
            this.streamLoadHelper = null;
        }
    }

    /** 各产品的专用写入器已各自检查。这里挡住仍走本基类的装载。 */
    private boolean streamLoadVerified() {
        var capability = OlapStreamLoadProfiles.of(getDatabaseProductName()).writeCopyCapability();
        return capability != null && capability.status() == OperationCapability.Status.VERIFIED;
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String reason) throws E {
        throw (E) new SQLFeatureNotSupportedException(reason, "0A000");
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fieldNames, List<Object[]> recordValues) {
        if (recordValues.isEmpty()) {
            return 0;
        }

        recordValues = BatchValueConverter.convert(recordValues, fieldNames.size(), batchLimits);

        // 选中 Stream Load 后异常不得触发另一种写入；远端可能已经接受本批数据。
        if (streamLoadHelper != null) {
            try {
                long count = streamLoadHelper.write(fieldNames, recordValues);
                if (log.isDebugEnabled()) {
                    log.debug("{} stream load write data affect count:{}", getDatabaseProductName(), count);
                }
                recordValues.clear();
                return count;
            } catch (UnconfirmedWriteException e) {
                throw e;
            } catch (Exception e) {
                log.warn("{} stream load outcome unconfirmed; automatic rewrite disabled (cause type: {})",
                        getDatabaseProductName(), e.getClass().getSimpleName());
                throw new UnconfirmedWriteException();
            }
        }

        List<String> placeHolders = Collections.nCopies(fieldNames.size(), "?");
        String sqlInsert = String.format("INSERT INTO `%s`.`%s` ( `%s` ) VALUES ( %s )",
                schemaName, tableName,
                StringUtils.join(fieldNames, "`,`"),
                StringUtils.join(placeHolders, ","));

        int[] argTypes = new int[fieldNames.size()];
        for (int i = 0; i < fieldNames.size(); ++i) {
            String col = fieldNames.get(i);
            argTypes[i] = this.columnType.get(col);
        }

        batchWrite(fieldNames, recordValues, sqlInsert, argTypes);

        int size = recordValues.size();
        if (log.isDebugEnabled()) {
            log.debug("{} insert write data affect count:{}", getDatabaseProductName(), size);
        }

        recordValues.clear();
        return size;
    }
}
