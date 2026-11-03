package cn.iocoder.yudao.framework.dbswitch.dbwriter.opengauss;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCompatibleInsertWriterImpl;

import javax.sql.DataSource;

/**
 * openGauss 数据库 INSERT 写入实现。
 */
public class OpengaussInsertWriterImpl extends PostgresCompatibleInsertWriterImpl implements IDatabaseWriter {

    /**
     * 初始化 {@code OpengaussInsertWriterImpl}及其父类状态。
     */
    public OpengaussInsertWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "OpenGauss";
    }
}
