package cn.iocoder.yudao.framework.dbswitch.dbwriter.sybase;


import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerWriterImpl;

import javax.sql.DataSource;

/**
 * Sybase批量写入实现类
 *
 * @author tang
 */
@Slf4j
public class SybaseWriterImpl extends SqlServerWriterImpl {

    /**
     * 初始化 {@code SybaseWriterImpl}及其父类状态。
     */
    public SybaseWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Sybase";
    }

}
