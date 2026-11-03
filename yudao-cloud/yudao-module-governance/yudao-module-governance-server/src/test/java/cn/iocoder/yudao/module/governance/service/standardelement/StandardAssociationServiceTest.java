package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardMappingMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StandardAssociationServiceTest extends BaseMockitoUnitTest {
    @InjectMocks private StandardAssociationService service;
    @Mock private StandardElementMapper elements;
    @Mock private StandardCodeSetMapper codeSets;
    @Mock private StandardMappingMapper mappings;
    @Mock private MetaColumnMapper columns;
    @Mock private MetaTableMapper tables;
    @Mock private SecurityFrameworkService security;

    @BeforeEach void login() {
        for (var type : List.of(StandardMappingDO.class, MetaColumnDO.class, MetaTableDO.class, StandardElementDO.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void wrongDirectoryPermissionCannotReadOwnerExistence() {
        assertThrows(ServiceException.class, () -> service.getColumns("codeset", 10));
        verify(security).hasPermission("governance:standard-code-set:query");
        verifyNoInteractions(codeSets, elements, mappings);
    }

    @Test void missingOwnerFailsInsteadOfReturningAnUnrelatedEmptyList() {
        when(security.hasPermission(anyString())).thenReturn(true);
        assertEquals(STANDARD_ELEMENT_NOT_EXISTS.getCode(), assertThrows(ServiceException.class,
                () -> service.getColumns("element", 10)).getCode());
        assertEquals(STANDARD_CODE_SET_NOT_EXISTS.getCode(), assertThrows(ServiceException.class,
                () -> service.getColumns("codeset", 10)).getCode());
        verifyNoInteractions(mappings);
    }

    @Test void emptyCodeSetHasNoMetadataQuery() {
        when(security.hasPermission(anyString())).thenReturn(true);
        when(codeSets.selectById(10)).thenReturn(StandardCodeSetDO.builder().id(10).build());
        when(elements.selectList(any(SFunction.class), eq(10))).thenReturn(List.of());
        assertTrue(service.getColumns("codeset", 10).isEmpty());
        verifyNoInteractions(mappings, columns, tables);
    }

    @Test void staleProjectionAndMissingTablesAreExcludedAndColumnsAreDeduplicated() {
        when(security.hasPermission(anyString())).thenReturn(true);
        when(elements.selectById(10)).thenReturn(StandardElementDO.builder().id(10).build());
        when(mappings.selectList(any(Wrapper.class))).thenReturn(List.of(fact(1L, 10), fact(1L, 10), fact(2L, 10), fact(3L, 10)));
        when(columns.selectList(any(Wrapper.class))).thenReturn(List.of(column(1L, 20L, 10L), column(2L, 20L, 11L), column(3L, 21L, 10L)));
        when(tables.selectList(any(Wrapper.class))).thenReturn(List.of(MetaTableDO.builder().id(20L).tableName("t_orders").build()));
        var result = service.getColumns("element", 10);
        assertEquals(1, result.size());
        assertEquals(1L, result.getFirst().getColumnId());
        assertEquals("t_orders", result.getFirst().getTableName());
        assertEquals("field_1", result.getFirst().getColumnName());
    }

    @Test void nullAndOutOfRangeIdsNeverReachPersistence() {
        assertThrows(ServiceException.class, () -> service.getColumns(null, 10));
        assertThrows(ServiceException.class, () -> service.getColumns("element", 0));
        assertThrows(ServiceException.class, () -> service.getColumns("unknown", 10));
        verifyNoInteractions(elements, codeSets, mappings);
    }

    @Test void anonymousCallFailsEvenIfPermissionProviderIsPermissive() {
        SecurityContextHolder.clearContext();
        assertThrows(ServiceException.class, () -> service.getColumns("element", 10));
        verifyNoInteractions(elements, security);
    }

    private StandardMappingDO fact(long column, int element) {
        return StandardMappingDO.builder().columnId(column).elementId(element)
                .targetType(StandardMappingDO.TARGET_TYPE_METADATA_COLUMN).mappingStatus(StandardMappingDO.STATUS_CONFIRMED).build();
    }
    private MetaColumnDO column(long id, long table, long element) {
        return MetaColumnDO.builder().id(id).tableId(table).standardElementId(element).columnName("field_" + id).build();
    }
}
