package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbchange.ChangeCalculatorService;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationGroupRewriteBindingTest {
    @Test void requestSnapshotPreservesThePhysicalPinButNeverAcceptsItFromJson() throws Exception {
        var request=new MigrationRequest();var binding=binding(()->{});
        GroupRewriteBindingProvider provider=(schema,table,logical,physical,structure)->binding;
        request.getTarget().setGroupRewriteBindingProvider(provider);
        request.getTarget().setGroupRewriteBindings(new HashMap<>(Map.of(TargetDataSourceProperties.groupRewriteTargetKey("dst","renamed"),binding)));
        var frozen=request.snapshot();request.getTarget().getGroupRewriteBindings().clear();
        assertSame(binding,frozen.getTarget().groupRewriteBinding("dst","renamed"));
        assertSame(provider,frozen.getTarget().getGroupRewriteBindingProvider());
        assertNull(frozen.getTarget().groupRewriteBinding("other","renamed"));
        assertThrows(UnsupportedOperationException.class,()->frozen.getTarget().getGroupRewriteBindings().clear());
        var mapper=new tools.jackson.databind.ObjectMapper();
        String json=mapper.writeValueAsString(frozen.getTarget());assertFalse(json.contains("groupRewriteBindings"));assertFalse(json.contains("groupRewriteBindingProvider"));
        var decoded=mapper.readValue("{\"groupRewriteBindings\":{\"forged\":{\"physicalFingerprint\":\""+"a".repeat(64)+"\"}},\"groupRewriteBindingProvider\":{}}",TargetDataSourceProperties.class);
        assertNull(decoded.getGroupRewriteBindings());assertNull(decoded.getGroupRewriteBindingProvider());
    }
    @Test void finalTargetRouteBindsBeforePrepareAndBeforeAnyDifferenceRead() {
        exercise(false,true);
    }
    @Test void revokedOwnershipStopsBeforePrepareOrDifferenceRead() {
        exercise(true,true);
    }
    @Test void unmatchedExpandedTargetReceivesTheTrustedProviderBeforePrepare() {
        exercise(false,false);
    }
    private void exercise(boolean revoked,boolean fixed) {
        var request=new MigrationRequest();var input=new SourceDataSourceProperties();input.setSourceProductType(ProductTypeEnum.TDENGINE);input.setSourceSchema("src");request.setSource(List.of(input));
        input.setRegexTableMapper(List.of(new cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper("^original$","renamed")));
        request.getTarget().setTargetProductType(ProductTypeEnum.TDENGINE);request.getTarget().setTargetSchema("dst");request.getTarget().setTargetTable("renamed");
        var events=new ArrayList<String>();var binding=binding(()->{events.add("ownership");if(revoked)throw new IllegalStateException("Owner revoked");});
        if(fixed)request.getTarget().setGroupRewriteBindings(Map.of(TargetDataSourceProperties.groupRewriteTargetKey("dst","renamed"),binding));
        var provider=mock(GroupRewriteBindingProvider.class);request.getTarget().setGroupRewriteBindingProvider(provider);
        var table=new TableDescription();table.setSchemaName("src");table.setTableName("original");
        var field=new ColumnDescription();field.setFieldName("id");field.setLabelName("id");
        try(var source=new HikariDataSource();var target=new HikariDataSource();var runtime=spy(DbSwitchRuntime.standalone());
            var calculators=mockConstruction(ChangeCalculatorService.class)) {
            var writer=mock(IDatabaseWriter.class);when(writer.getDataSource()).thenReturn(target);
            var synchronizer=mock(IDatabaseSynchronize.class);doReturn(synchronizer).when(runtime).synchronizer(target,ProductTypeEnum.TDENGINE);
            doAnswer(call->{events.add("bind");return null;}).when(synchronizer).bindGroupRewrite(binding);
            doAnswer(call->{events.add("provider");return null;}).when(synchronizer).bindGroupRewriteProvider(provider);
            doAnswer(call->{events.add("prepare");return null;}).when(synchronizer).prepare(anyString(),anyString(),anyList(),anyList());
            var handler=MigrationHandler.createInstance(table,request,0,source,target,null,runtime);
            ReflectionTestUtils.setField(handler,"sourceProductType",ProductTypeEnum.TDENGINE);ReflectionTestUtils.setField(handler,"targetProductType",ProductTypeEnum.TDENGINE);
            ReflectionTestUtils.setField(handler,"sourceColumnDescriptions",List.of(field));ReflectionTestUtils.setField(handler,"targetColumnDescriptions",List.of(field));
            ReflectionTestUtils.setField(handler,"sourcePrimaryKeys",List.of("id"));ReflectionTestUtils.setField(handler,"targetPrimaryKeys",List.of("id"));
            if(revoked) {
                assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(handler,"doIncreaseSynchronize",writer,new DbSwitchTableResult()));
                assertEquals(List.of("ownership"),events);verify(synchronizer,never()).prepare(anyString(),anyString(),anyList(),anyList());assertTrue(calculators.constructed().isEmpty());
            } else {
                ReflectionTestUtils.invokeMethod(handler,"doIncreaseSynchronize",writer,new DbSwitchTableResult());
                assertEquals(fixed?List.of("ownership","bind","prepare"):List.of("provider","prepare"),events);assertEquals(1,calculators.constructed().size());
                if(fixed){verify(synchronizer).bindGroupRewrite(same(binding));verify(synchronizer,never()).bindGroupRewriteProvider(any());}
                else {verify(synchronizer).bindGroupRewriteProvider(same(provider));verify(synchronizer,never()).bindGroupRewrite(any());}
            }
            verifyNoInteractions(provider);
        }
    }
    private GroupRewriteBinding binding(Runnable ownership) {
        return new GroupRewriteBinding("a".repeat(64),"b".repeat(64),"c".repeat(64),"fixture_owner_0001",mock(GroupRewriteStore.class),BatchLimits.defaults(),true,GroupRewriteBinding.ResumeMode.NEW_OPERATION_ONLY,ownership);
    }
}
