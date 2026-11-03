package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SequenceTransferBindingTest {
    @Test void fabricatedBindingCannotDisableInsertIdentityGuard() {
        var source=mock(DataSource.class);var binding=mock(SequenceTransferScope.TableBinding.class);
        assertThrows(IllegalArgumentException.class,()->new PostgresInsertWriterImpl(source).configureSequenceTransfer(binding));
        verifyNoInteractions(source,binding);
    }
    @Test void fabricatedBindingCannotDisableCopyIdentityGuard() {
        var source=mock(DataSource.class);var binding=mock(SequenceTransferScope.TableBinding.class);
        assertThrows(IllegalArgumentException.class,()->new PostgresCopyWriterImpl(source).configureSequenceTransfer(binding));
        verifyNoInteractions(source,binding);
    }
}
