package cn.iocoder.yudao.framework.dbswitch.dbwriter.kingbase;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCompatibleCopyWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.kingbase.KingbaseProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;

import javax.sql.DataSource;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Kingbase COPY 只有在本产品 WRITE_COPY 为 VERIFIED 时才沿用父类协议。
 * 不能因为其他 PostgreSQL 兼容产品已验证就放行；写入与运行前预检都使用本产品的声明。
 */
public class KingbaseCopyWriterImpl extends PostgresCompatibleCopyWriterImpl implements IDatabaseWriter {

    /**
     * 初始化 {@code KingbaseCopyWriterImpl}及其父类状态。
     */
    public KingbaseCopyWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Kingbase";
    }

    /** 清单不是 VERIFIED 时停在入口，不进入共享的 COPY 协议。 */
    @Override
    protected void requireCopyAdmission() {
        var capability = new KingbaseProductProvider().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.KINGBASE)
                .map(product -> product.capabilities().get(DatabaseOperation.WRITE_COPY))
                .findFirst().orElse(null);
        if (capability != null && capability.status() == OperationCapability.Status.VERIFIED) {
            return;
        }
        String state = capability == null ? "UNDECLARED" : capability.status() + "/" + capability.reason();
        unsupported("Kingbase WRITE_COPY is not verified (" + state + ")");
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String reason) throws E {
        throw (E) new SQLFeatureNotSupportedException(reason, "0A000");
    }

}
