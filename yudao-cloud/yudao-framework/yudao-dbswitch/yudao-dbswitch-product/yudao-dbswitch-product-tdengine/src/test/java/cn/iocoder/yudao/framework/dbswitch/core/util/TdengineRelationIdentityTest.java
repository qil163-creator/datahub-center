package cn.iocoder.yudao.framework.dbswitch.core.util;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TdengineRelationIdentityTest {
    private final Connection connection=mock(Connection.class);
    private final List<Statement> statements=new ArrayList<>();
    private final List<ResultSet> results=new ArrayList<>();
    private String version="3.3.8.8",edition="community",host="node-a",type="NORMAL_TABLE",uid="12345";
    private String enabled="1",sysinfo="1",grant="read";
    private int condition;
    private boolean changeGeneration;
    private int relationQueries;

    @BeforeEach void setup() throws Exception {
        when(connection.createStatement()).thenAnswer(call->{
            var statement=mock(Statement.class);statements.add(statement);
            when(statement.executeQuery(anyString())).thenAnswer(query->rows(query.getArgument(0)));
            return statement;
        });
    }
    private ResultSet rows(String sql) throws SQLException {
        String[] fields;
        if(sql.contains("server_version()"))fields=new String[]{version,"reader@"+host,"source_db"};
        else if(sql.contains("ins_cluster"))fields=new String[]{"123456","native-cluster",edition};
        else if(sql.contains("ins_users "))fields=new String[]{"reader","1000","0",enabled,sysinfo};
        else if(sql.contains("ins_databases"))fields=new String[]{"2000","ms","ready"};
        else if(sql.contains("ins_tables"))fields=new String[]{"sample","source_db",changeGeneration&&++relationQueries>1?"54321":uid,"3000",type,""};
        else if(sql.contains("ins_user_privileges"))fields=new String[]{grant,"source_db","",Integer.toString(condition)};
        else throw new AssertionError("Unexpected native query");
        var rows=mock(ResultSet.class);results.add(rows);when(rows.next()).thenReturn(true,false);
        when(rows.getString(anyInt())).thenAnswer(call->fields[(int)call.getArgument(0)-1]);
        when(rows.getInt(anyInt())).thenAnswer(call->Integer.parseInt(fields[(int)call.getArgument(0)-1]));
        return rows;
    }
    @Test void sameClusterGenerationAndAccountDoNotDependOnServingHostname() throws Exception {
        String first=TdengineRelationIdentity.fingerprint(connection,"source_db.sample");
        host="node-b";assertEquals(first,TdengineRelationIdentity.fingerprint(connection,"sample"));
        uid="54321";assertNotEquals(first,TdengineRelationIdentity.fingerprint(connection,"source_db.sample"));
        assertTrue(first.matches("[a-f0-9]{64}"));
        for(var statement:statements){verify(statement).setQueryTimeout(5);verify(statement).close();}
        for(var rows:results)verify(rows).close();
        verify(connection,never()).close();verify(connection,never()).commit();verify(connection,never()).setAutoCommit(anyBoolean());
    }
    @ParameterizedTest @ValueSource(strings={"sample;DROP TABLE sample","source_db.sample.extra","`sample`","information_schema.sample","performance_schema.sample"})
    void complexAndSystemRelationsCannotResolve(String name) {
        assertThrows(SQLFeatureNotSupportedException.class,()->TdengineRelationIdentity.fingerprint(connection,name));
    }
    @Test void unverifiedVersionsAndEditionsNeverAcquireNativeProof() {
        version="3.4.0.0";assertThrows(SQLFeatureNotSupportedException.class,()->TdengineRelationIdentity.fingerprint(connection,"sample"));
        version="3.3.8.8";edition="enterprise";assertThrows(SQLFeatureNotSupportedException.class,()->TdengineRelationIdentity.fingerprint(connection,"sample"));
    }
    @Test void conditionalMissingDisabledOrInvisibleGrantsCannotProveCoverage() {
        condition=1;assertRejected();condition=0;grant="write";assertRejected();grant="read";
        enabled="0";assertRejected();enabled="1";sysinfo="0";assertRejected();
    }
    @Test void absentOrNonphysicalGenerationIsRejected() {
        uid=null;assertRejected();uid="12345";type="CHILD_TABLE";assertRejected();
    }
    @Test void generationRaceIsRejected() {changeGeneration=true;assertRejected();}
    @Test void nativeQueryFailurePropagatesAndClosesOnlyStatement() throws Exception {
        var failure=new SQLTimeoutException("synthetic timeout");var statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenThrow(failure);
        assertSame(failure,assertThrows(SQLTimeoutException.class,()->TdengineRelationIdentity.fingerprint(connection,"sample")));
        verify(statement).close();verify(connection,never()).close();
    }
    private void assertRejected(){assertThrows(SQLFeatureNotSupportedException.class,()->TdengineRelationIdentity.fingerprint(connection,"sample"));}
}
