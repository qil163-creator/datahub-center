package cn.iocoder.yudao.framework.dbswitch.data.domain;

import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据迁移任务整体结果,以原子计数汇总迁移的表数、成功/失败表数、总行数与字节数,并记录各表明细结果。
 *
 * @ClassName DbSwitchResult
 * @Author zrx
 * @Date 2022/10/28 9:36
 */
@Data
public class DbSwitchResult {

    private MigrationTermination termination = MigrationTermination.UNCONFIRMED;

    private AtomicBoolean ifAllSuccess = new AtomicBoolean(true);

    private AtomicLong totalTableCount = new AtomicLong(0);

    private AtomicLong totalTableSuccessCount = new AtomicLong(0);

    private AtomicLong totalTableFailCount = new AtomicLong(0);

    private AtomicLong totalRowCount = new AtomicLong(0);

    private AtomicLong totalBytes = new AtomicLong(0);

    private List<DbSwitchTableResult> tableResultList = new CopyOnWriteArrayList<>();
}
