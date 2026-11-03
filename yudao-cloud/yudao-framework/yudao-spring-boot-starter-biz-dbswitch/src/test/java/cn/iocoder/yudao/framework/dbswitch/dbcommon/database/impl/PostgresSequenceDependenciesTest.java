package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.core.model.SequenceDependencyGraph;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresSequenceDependenciesTest {
    @Test void unboundedOrDuplicateRequestsFailBeforeOpeningConnection() {
        var source=mock(DataSource.class);var reader=new PostgreSqlDatabaseOperator(source);
        var relation=new TableReadPlan.Relation("s","t");
        assertThrows(IllegalArgumentException.class,()->reader.planMigrationDependencies(List.of()));
        assertThrows(IllegalArgumentException.class,()->reader.planMigrationDependencies(List.of(relation,relation)));
        var excessive=IntStream.rangeClosed(0,SequenceDependencyGraph.MAX_RELATIONS).mapToObj(i->new TableReadPlan.Relation("s","t"+i)).toList();
        assertThrows(IllegalArgumentException.class,()->reader.planMigrationDependencies(excessive));
        verifyNoInteractions(source);
    }
    @Test void compatibleProductsDoNotInheritPostgreSqlCatalogCertification() {
        var source=mock(DataSource.class);
        assertThrows(UnsupportedOperationException.class,()->new GreenplumDatabaseOperator(source).planMigrationDependencies(List.of(new TableReadPlan.Relation("s","t"))));
        assertThrows(UnsupportedOperationException.class,()->new UxdbDatabaseOperator(source).planMigrationDependencies(List.of(new TableReadPlan.Relation("s","t"))));
        verifyNoInteractions(source);
    }
}
