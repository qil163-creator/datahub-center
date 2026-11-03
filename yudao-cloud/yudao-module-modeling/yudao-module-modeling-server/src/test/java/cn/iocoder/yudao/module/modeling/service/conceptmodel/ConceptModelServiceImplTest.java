package cn.iocoder.yudao.module.modeling.service.conceptmodel;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.conceptmodel.ConceptModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.conceptmodel.ConceptModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConceptModelServiceImplTest {
    @Mock private ConceptModelMapper mapper;
    @Mock private ModelHierarchyResolver hierarchyResolver;
    @Mock private DataDomainMapper dataDomainMapper;
    @Mock private SubjectDomainMapper subjectDomainMapper;
    @InjectMocks private ConceptModelServiceImpl service;

    @BeforeAll
    static void tableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConceptModelDO.class);
    }

    @Test
    void createsNormalizedDraftAndDoesNotMutateCallerDesign() {
        var request = request();
        when(mapper.insert(any(ConceptModelDO.class))).thenAnswer(call -> {
            ConceptModelDO row = call.getArgument(0);
            row.setId(9007199254740993L);
            assertEquals("customer", row.getCode());
            assertEquals("DRAFT", row.getStatus());
            assertEquals(0L, row.getRevision());
            var design = JsonUtils.parseObject(row.getDesign(), ConceptModelDesignVO.class);
            assertEquals("customer", design.getEntities().getFirst().getCode());
            assertEquals("entity-1", design.getEntities().getFirst().getKey());
            assertEquals("OBJECT", design.getEntities().getFirst().getEntityType());
            return 1;
        });
        assertEquals(9007199254740993L, service.create(request));
        assertEquals("Customer", request.getDesign().getEntities().getFirst().getCode());
        verify(hierarchyResolver).validateModelDataDomain(1L);
    }

    @Test
    void persistsActivitiesAndRejectsUnknownPlanningTypes() {
        var request = request();
        request.getDesign().getEntities().getFirst().setEntityType("ACTIVITY");
        when(mapper.insert(any(ConceptModelDO.class))).thenAnswer(call -> {
            var row = call.<ConceptModelDO>getArgument(0);
            assertEquals("ACTIVITY", JsonUtils.parseObject(row.getDesign(), ConceptModelDesignVO.class)
                    .getEntities().getFirst().getEntityType());
            row.setId(100L);
            return 1;
        });
        assertEquals(100L, service.create(request));
        request.getDesign().getEntities().getFirst().setEntityType("OTHER");
        assertError(CONCEPT_DESIGN_INVALID.getCode(), () -> service.create(request));
        verify(mapper, times(1)).insert(any(ConceptModelDO.class));
    }

    @Test
    void sameDisplayNameWithDistinctIdentityCanBeSaved() {
        var request = request();
        request.getDesign().getEntities().add(entity("entity-2", request.getDesign().getEntities().getFirst().getName(), "customer2"));
        when(mapper.insert(any(ConceptModelDO.class))).thenAnswer(call -> {
            var row = call.<ConceptModelDO>getArgument(0);
            var design = JsonUtils.parseObject(row.getDesign(), ConceptModelDesignVO.class);
            assertEquals(2, design.getEntities().size());
            row.setId(101L);
            return 1;
        });
        assertEquals(101L, service.create(request));
    }

    @Test
    void rejectsDuplicateEntityCodesAndDanglingRelationshipsBeforeWrite() {
        var request = request();
        var second = entity("entity-2", "客户2", "CUSTOMER");
        request.getDesign().getEntities().add(second);
        assertError(CONCEPT_DESIGN_INVALID.getCode(), () -> service.create(request));
        request.getDesign().getEntities().removeLast();
        var relationship = new ConceptModelDesignVO.Relation();
        relationship.setKey("r1");
        relationship.setName("拥有");
        relationship.setSourceEntityKey("entity-1");
        relationship.setTargetEntityKey("missing");
        relationship.setCardinality("ONE_TO_MANY");
        request.getDesign().getRelations().add(relationship);
        assertError(CONCEPT_DESIGN_INVALID.getCode(), () -> service.create(request));
        verify(mapper, never()).insert(any(ConceptModelDO.class));
    }

    @Test
    void rejectsDuplicateAttributeCodesWithinAnEntity() {
        var request = request();
        var first = new ConceptModelDesignVO.Attribute();
        first.setKey("a1"); first.setName("编号"); first.setCode("Id");
        var second = new ConceptModelDesignVO.Attribute();
        second.setKey("a2"); second.setName("另一个编号"); second.setCode("ID");
        request.getDesign().getEntities().getFirst().getAttributes().addAll(List.of(first, second));
        assertError(CONCEPT_DESIGN_INVALID.getCode(), () -> service.create(request));
        verify(mapper, never()).insert(any(ConceptModelDO.class));
    }

    @Test
    void activeDefinitionCannotBeEditedOrDeleted() {
        when(mapper.selectById(1L)).thenReturn(row("ACTIVE", 3));
        var request = updateRequest(); request.setExpectedRevision(3L);
        assertError(CONCEPT_MODEL_NOT_DRAFT.getCode(), () -> service.update(request));
        assertError(CONCEPT_MODEL_NOT_DRAFT.getCode(), () -> service.delete(1L, 3L));
        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).deleteDraft(anyLong(), anyLong(), anyString());
    }

    @Test
    void staleReadAndLosingCasAreRejectedAndJsonUpdateUsesHandlerAndAuditFields() {
        when(mapper.selectById(1L)).thenReturn(row("DRAFT", 3));
        var request = updateRequest();
        assertError(CONCEPT_MODEL_REVISION_CONFLICT.getCode(), () -> service.update(request));
        request.setExpectedRevision(3L);
        when(mapper.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<ConceptModelDO> wrapper = call.getArgument(1);
            assertTrue(wrapper.getSqlSet().contains("JsonbStringTypeHandler"));
            assertTrue(wrapper.getSqlSet().contains("update_time"));
            assertTrue(wrapper.getSqlSet().contains("updater"));
            assertTrue(wrapper.getSqlSegment().contains("revision"));
            assertTrue(wrapper.getSqlSegment().contains("status"));
            return 0;
        });
        assertError(CONCEPT_MODEL_REVISION_CONFLICT.getCode(), () -> service.update(request));
    }

    @Test
    void activationRejectsEmptyDesignAndAllowsValidDesign() {
        var existing = row("DRAFT", 0);
        existing.setDesign("{\"entities\":[],\"relations\":[]}");
        when(mapper.selectById(1L)).thenReturn(existing);
        var request = new ConceptModelStatusReqVO();
        request.setId(1L); request.setExpectedRevision(0L); request.setStatus("ACTIVE");
        assertError(CONCEPT_MODEL_EMPTY.getCode(), () -> service.updateStatus(request));
        existing.setDesign(JsonUtils.toJsonString(request().getDesign()));
        when(mapper.update(isNull(), any())).thenReturn(1);
        service.updateStatus(request);
    }

    @Test
    void databaseUniquenessIsTranslatedWithoutQueryingAbortedTransaction() {
        when(mapper.insert(any(ConceptModelDO.class))).thenThrow(new DuplicateKeyException("fixture"));
        assertError(CONCEPT_MODEL_DUPLICATE.getCode(), () -> service.create(request()));
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    void nestedValidationRejectsUnsafeKeysEnumsAndUnboundedCollections() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = request();
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.getDesign().getEntities().getFirst().setKey("invalid key");
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.getDesign().getEntities().getFirst().setKey("valid-key");
            request.getDesign().setEntities(new ArrayList<>(java.util.Collections.nCopies(51,
                    request.getDesign().getEntities().getFirst())));
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }

    private ConceptModelSaveReqVO request() {
        var result = new ConceptModelSaveReqVO();
        result.setName("客户模型"); result.setCode("Customer"); result.setDataDomainId(1L);
        var design = new ConceptModelDesignVO();
        design.getEntities().add(entity("entity-1", "客户", "Customer"));
        result.setDesign(design);
        return result;
    }

    private ConceptModelUpdateReqVO updateRequest() {
        var result = new ConceptModelUpdateReqVO();
        var source = request();
        result.setId(1L); result.setExpectedRevision(0L); result.setName(source.getName());
        result.setCode(source.getCode()); result.setDataDomainId(source.getDataDomainId());
        result.setDesign(source.getDesign());
        return result;
    }

    private ConceptModelDesignVO.Entity entity(String key, String name, String code) {
        var entity = new ConceptModelDesignVO.Entity();
        entity.setKey(key); entity.setName(name); entity.setCode(code);
        return entity;
    }

    private ConceptModelDO row(String status, long revision) {
        var row = new ConceptModelDO();
        row.setId(1L); row.setDataDomainId(1L); row.setStatus(status); row.setRevision(revision);
        row.setDesign(JsonUtils.toJsonString(request().getDesign()));
        return row;
    }

    private void assertError(int code, Runnable action) {
        assertEquals(code, assertThrows(ServiceException.class, action::run).getCode());
    }
}
