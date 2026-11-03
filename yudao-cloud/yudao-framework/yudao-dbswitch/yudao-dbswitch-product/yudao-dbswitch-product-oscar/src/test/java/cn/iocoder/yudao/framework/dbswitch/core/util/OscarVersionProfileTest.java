package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OscarVersionProfileTest {
    private static String version(int days) { return OscarNativeCatalog.SERVER_BUILD + " (license invalid after " + days + " days)"; }
    @Test void liveLicenseDaysDoNotImplyAnotherBuild() {
        for (int days : List.of(180, 179, 2, 1)) assertTrue(OscarNativeCatalog.verifiedVersion(version(days)));
        for (String value : List.of(version(0), version(-1), version(181), version(179).replace("251217", "251218"),
                version(179).replace("2000", "3000"), version(179).replace("Linux", "Windows"), version(179) + " other", OscarNativeCatalog.SERVER_BUILD))
            assertFalse(OscarNativeCatalog.verifiedVersion(value));
        assertFalse(OscarNativeCatalog.verifiedVersion(null));
    }
    @Test void originalBuildIdentitySurvivesLicenseCountdownIncludingMidObservation() throws Exception {
        assertEquals(OscarRelationIdentity.fingerprint(connection(180, 180), "OWNER.TABLE"),
                OscarRelationIdentity.fingerprint(connection(180, 179), "OWNER.TABLE"));
        assertEquals(OscarRelationIdentity.fingerprint(connection(180, 180), "OWNER.TABLE"),
                OscarRelationIdentity.fingerprint(connection(179, 179), "OWNER.TABLE"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> OscarRelationIdentity.fingerprint(connection(0, 0), "OWNER.TABLE"));
    }
    @Test void nativeProvenanceKeepsObservedLicenseWhileRecognizingTheVerifiedBuild() {
        for (int days : List.of(180, 179, 1, 0)) {
            var provenance = new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(
                    cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OSCAR, "OSCAR", version(days),
                    OscarNativeCatalog.METHOD, java.time.Instant.EPOCH);
            assertEquals(days > 0, provenance.nativeProduct(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OSCAR));
            assertEquals(version(days), provenance.serverVersion());
        }
    }
    private static Connection connection(int first, int next) throws Exception {
        var c = mock(Connection.class); var p = mock(PreparedStatement.class); var count = new AtomicInteger();
        when(c.prepareStatement(anyString())).thenReturn(p);
        when(p.executeQuery()).thenAnswer(call -> {
            var r = mock(ResultSet.class); when(r.next()).thenReturn(true, false);
            String[] values = {version(count.getAndIncrement() == 0 ? first : next), "FIXTURE", "OSRDB", "1", "OWNER", "2", "TABLE", "3", "4", "0"};
            for (int i = 0; i < values.length; i++) when(r.getString(i + 1)).thenReturn(values[i]);
            when(r.getBoolean(11)).thenReturn(true); return r;
        });
        return c;
    }
}
