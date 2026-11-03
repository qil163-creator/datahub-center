package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OscarCursorBoundaryTest {

  @Test
  void publicRegistryReaderRejectsCursorWithoutDeploymentPinBeforeBorrowingConnection() {
    DataSource source = mock(DataSource.class);
    CursorReadRequest request = mock(CursorReadRequest.class);
    IDatabaseOperator reader = DbSwitchProductRegistry.discover().reader(source, ProductTypeEnum.OSCAR);

    assertThrows(IllegalArgumentException.class, () -> reader.openCursor(request));

    verifyNoInteractions(source);
  }
}
