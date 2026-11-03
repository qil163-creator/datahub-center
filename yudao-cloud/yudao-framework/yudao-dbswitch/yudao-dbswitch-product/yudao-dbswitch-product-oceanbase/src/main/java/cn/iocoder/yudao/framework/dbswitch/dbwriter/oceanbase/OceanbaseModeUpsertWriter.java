package cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.util.OceanbaseUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle.OracleUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseOracleReplayProfile;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/** prepare 时选择租户的产品自有重放配置；构造过程绝不打开连接。
 * 每个委托实现均在每个写入连接上重新观测自身版本、模式、结构及键契约。 */
public final class OceanbaseModeUpsertWriter implements IDatabaseUpsertWriter {

    private final DataSource source;

    private BatchLimits limits = BatchLimits.defaults();

    private DbSwitchOptions options = DbSwitchOptions.defaults();

    private IDatabaseUpsertWriter delegate;

    /**
     * 校验构造参数并初始化 {@code OceanbaseModeUpsertWriter}。
     */
    public OceanbaseModeUpsertWriter(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    /**
     * 获取数据源对象。
     */
    @Override
    public DataSource getDataSource() {
        return source;
    }

    /**
     * prepare/write 前配置操作实例；提供方必须显式接受自定义限额。
     */
    @Override
    public void configureBatchLimits(BatchLimits limits) {
        this.limits = Objects.requireNonNull(limits);
        if (delegate != null) {
            delegate.configureBatchLimits(limits);
        }
    }

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    @Override
    public void configure(DbSwitchOptions options) {
        this.options = Objects.requireNonNull(options);
        if (delegate != null) {
            delegate.configure(options);
        }
    }

    /**
     * 不支持的键、索引或存储语义必须在发送任何数据语句前失败。
     */
    @Override
    public void prepareUpsert(String schema, String table, List<String> fields, List<String> keys) {
        delegate = null;
        IDatabaseUpsertWriter candidate = OceanbaseUtils.isOceanBaseUseMysqlMode(source)
                ? new OceanbaseUpsertWriter(source) : new OracleUpsertWriter(source,
                OceanbaseOracleReplayProfile.INSTANCE);
        candidate.configure(options);
        candidate.configureBatchLimits(limits);
        candidate.prepareUpsert(schema, table, fields, keys);
        delegate = candidate;
    }

    /**
     * 返回当前已准备。
     */
    private IDatabaseUpsertWriter prepared() {
        if (delegate == null) {
            throw new IllegalStateException("OceanBase UPSERT target is not prepared");
        }
        return delegate;
    }

    /**
     * 返回已观测目标表的结构指纹。
     */
    @Override
    public String targetSchemaFingerprint() {
        return prepared().targetSchemaFingerprint();
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return prepared().keyContractFingerprint();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        return prepared().keyContract();
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> names, List<Object[]> rows) {
        return prepared().write(names, rows);
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public BatchWriteResult writeWithReceipt(List<String> names, List<Object[]> rows) {
        return prepared().writeWithReceipt(names, rows);
    }
}
