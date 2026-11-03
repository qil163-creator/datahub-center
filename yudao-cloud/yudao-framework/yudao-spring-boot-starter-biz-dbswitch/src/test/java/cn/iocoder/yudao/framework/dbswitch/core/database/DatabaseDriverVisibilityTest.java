package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseDriverVisibilityTest {
    @Test void metadataCanUseCallerConnectionWithoutClasspathDriver() throws Exception {
        var database = new DatabaseMysqlImpl("absent.vendor.Driver");
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);
        assertThat(database.queryTableList(connection, "fixture")).isEmpty();
        verify(rows).close(); verify(connection, never()).close();
        assertThat(database.getDriverClassName()).isEqualTo("absent.vendor.Driver");
    }

    @Test void unverifiedCustomConnectionDriverStillFailsBeforeOpeningAConnection() {
        var target = new TargetDataSourceProperties(); target.setTargetProductType(ProductTypeEnum.MYSQL);
        target.setDriverClassName("absent.vendor.Driver"); target.setUrl("jdbc:mysql://127.0.0.1:1/not_connected");
        assertThatThrownBy(() -> DataSourceUtils.createTargetDataSource(target)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Custom driver classes require a verified deployment bundle");
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = "UNKNOWN", mode = EnumSource.Mode.EXCLUDE)
    void allRegisteredMetadataFactoriesCanBeConstructed(ProductTypeEnum product) {
        // Factory reachability only, NOT proof of metadata/read/write/sync database compatibility.
        assertThat(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(product).getDatabaseType()).isEqualTo(product);
    }
}
