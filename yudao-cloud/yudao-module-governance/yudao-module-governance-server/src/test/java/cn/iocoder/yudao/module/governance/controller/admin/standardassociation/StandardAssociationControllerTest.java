package cn.iocoder.yudao.module.governance.controller.admin.standardassociation;

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
import cn.iocoder.yudao.module.governance.service.standardelement.StandardAssociationService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import jakarta.servlet.ServletException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_ELEMENT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StandardAssociationControllerTest extends BaseMockitoUnitTest {
    @InjectMocks private StandardAssociationService service;
    @Mock private StandardElementMapper elements;
    @Mock private StandardCodeSetMapper codeSets;
    @Mock private StandardMappingMapper mappings;
    @Mock private MetaColumnMapper columns;
    @Mock private MetaTableMapper tables;
    @Mock private SecurityFrameworkService security;

    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setup() {
        for (var type : List.of(StandardMappingDO.class, MetaColumnDO.class, MetaTableDO.class, StandardElementDO.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new StandardAssociationController(service)).setValidator(validator).build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        validator.close();
    }

    @Test
    void elementWithoutConfirmedMappingsReturnsFalse() throws Exception {
        when(security.hasPermission("governance:standard-element:query")).thenReturn(true);
        when(elements.selectById(10)).thenReturn(StandardElementDO.builder().id(10).build());
        when(mappings.selectList(any(Wrapper.class))).thenReturn(List.of());

        mvc.perform(get("/governance/standard-association/has-columns").param("kind", "element").param("id", "10"))
                .andExpect(status().isOk()).andExpect(content().json("{\"code\":0,\"data\":false}"));

        verifyNoInteractions(codeSets, columns, tables);
    }

    @Test
    void codeSetWithoutElementsReturnsFalse() throws Exception {
        when(security.hasPermission("governance:standard-code-set:query")).thenReturn(true);
        when(codeSets.selectById(10)).thenReturn(StandardCodeSetDO.builder().id(10).build());
        when(elements.selectList(any(SFunction.class), eq(10))).thenReturn(List.of());

        mvc.perform(get("/governance/standard-association/has-columns").param("kind", "codeset").param("id", "10"))
                .andExpect(status().isOk()).andExpect(content().json("{\"code\":0,\"data\":false}"));

        verifyNoInteractions(mappings, columns, tables);
    }

    @Test
    void effectiveConfirmedColumnReturnsTrue() throws Exception {
        when(security.hasPermission("governance:standard-element:query")).thenReturn(true);
        when(elements.selectById(10)).thenReturn(StandardElementDO.builder().id(10).build());
        when(mappings.selectList(any(Wrapper.class))).thenReturn(List.of(StandardMappingDO.builder()
                .columnId(1L).elementId(10).targetType(StandardMappingDO.TARGET_TYPE_METADATA_COLUMN)
                .mappingStatus(StandardMappingDO.STATUS_CONFIRMED).build()));
        when(columns.selectList(any(Wrapper.class))).thenReturn(List.of(MetaColumnDO.builder()
                .id(1L).tableId(20L).standardElementId(10L).columnName("id").build()));
        when(tables.selectList(any(Wrapper.class))).thenReturn(List.of(MetaTableDO.builder()
                .id(20L).tableName("t_orders").build()));

        mvc.perform(get("/governance/standard-association/has-columns").param("kind", "element").param("id", "10"))
                .andExpect(status().isOk()).andExpect(content().json("{\"code\":0,\"data\":true}"));
    }

    @Test
    void wrongDirectoryPermissionIsNotReportedAsNoAssociations() {
        var failure = assertThrows(ServletException.class, () -> mvc.perform(
                get("/governance/standard-association/has-columns").param("kind", "codeset").param("id", "10")));

        assertEquals(403, assertInstanceOf(ServiceException.class, failure.getCause()).getCode());
        verify(security).hasPermission("governance:standard-code-set:query");
        verifyNoInteractions(elements, codeSets, mappings, columns, tables);
    }

    @Test
    void nonexistentStandardsAreNotReportedAsNoAssociations() {
        when(security.hasPermission(anyString())).thenReturn(true);

        var elementFailure = assertThrows(ServletException.class, () -> mvc.perform(
                get("/governance/standard-association/has-columns").param("kind", "element").param("id", "10")));
        assertEquals(STANDARD_ELEMENT_NOT_EXISTS.getCode(),
                assertInstanceOf(ServiceException.class, elementFailure.getCause()).getCode());
        var codeSetFailure = assertThrows(ServletException.class, () -> mvc.perform(
                get("/governance/standard-association/has-columns").param("kind", "codeset").param("id", "10")));
        assertEquals(STANDARD_CODE_SET_NOT_EXISTS.getCode(),
                assertInstanceOf(ServiceException.class, codeSetFailure.getCause()).getCode());
        verifyNoInteractions(mappings, columns, tables);
    }

    @Test
    void invalidKindAndIdAreRejectedBeforeReadingStandards() throws Exception {
        mvc.perform(get("/governance/standard-association/has-columns").param("kind", "other").param("id", "10"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/governance/standard-association/has-columns").param("kind", "element").param("id", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/governance/standard-association/has-columns").param("kind", "element"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(security, elements, codeSets, mappings, columns, tables);
    }
}
