package cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCompatibleInsertWriterImpl;

import javax.sql.DataSource;

/**
 * Greenplum数据库Insert写入实现类
 *
 * @author jrl
 */
public class GreenplumInsertWriterImpl extends PostgresCompatibleInsertWriterImpl {

    /**
     * 初始化 {@code GreenplumInsertWriterImpl}及其父类状态。
     */
    public GreenplumInsertWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Greenplum";
    }

}
