package cn.iocoder.yudao.framework.dbswitch.dbwriter.kingbase;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCompatibleInsertWriterImpl;

import javax.sql.DataSource;

/**
 * Kingbase8数据库Insert写入实现类
 *
 * @author jrl
 */
public class KingbaseInsertWriterImpl extends PostgresCompatibleInsertWriterImpl implements IDatabaseWriter {

    /**
     * 初始化 {@code KingbaseInsertWriterImpl}及其父类状态。
     */
    public KingbaseInsertWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Kingbase";
    }

}
