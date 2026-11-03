package cn.iocoder.yudao.module.modeling.service.registration;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.biz.system.dict.dto.DictDataRespDTO;
import cn.iocoder.yudao.module.modeling.api.registration.dto.*;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.*;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainNamingRuleVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.*;
import cn.iocoder.yudao.module.modeling.service.planning.*;
import cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityService;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationContextServiceTest {
    private final RegistrationContextService service=new RegistrationContextService();
    private final DataDomainService domains=mock(DataDomainService.class);
    private final SubjectDomainService subjects=mock(SubjectDomainService.class);
    private final DwLayerService layers=mock(DwLayerService.class);
    private final BusinessEntityService entities=mock(BusinessEntityService.class);
    private final ModelHierarchyResolver hierarchy=mock(ModelHierarchyResolver.class);
    private final DictDataApi dictionaries=mock(DictDataApi.class);
    private DataDomainDO board;

    @BeforeEach void fixture() {
        ReflectionTestUtils.setField(service,"domains",domains);ReflectionTestUtils.setField(service,"subjects",subjects);
        ReflectionTestUtils.setField(service,"layers",layers);ReflectionTestUtils.setField(service,"entities",entities);
        ReflectionTestUtils.setField(service,"hierarchy",hierarchy);ReflectionTestUtils.setField(service,"dictionaries",dictionaries);
        board=DataDomainDO.builder().id(10L).name("水利").code("WATER").status(0).build();
        when(domains.getDataDomainSimpleList()).thenReturn(List.of(board,DataDomainDO.builder().id(11L).status(1).build()));
        when(hierarchy.validateModelDataDomain(10L)).thenReturn(board);
        when(subjects.getSubjectDomainList(any())).thenReturn(List.of(SubjectDomainDO.builder().id(20L).parentId(0L).dataDomainId(10L).name("河道水情").code("RIVER").abbreviation("RH").status(0).build()));
        when(layers.getDwLayerList(any())).thenReturn(List.of(DwLayerDO.builder().id(9L).layerName("自定义层").layerCode("LANDING").nodeType("LAYER").status((short)0).build()));
        var options=new BusinessEntityOptionsRespVO();options.setEntities(List.of(entity(30,"测站","ST","OBJECT","NORMAL"),entity(32,"区域","REG","OBJECT","NORMAL"),entity(40,"降水事件","RAIN","ACTIVITY","EVENT"),entity(41,"调度流程","FLOW","ACTIVITY","PROCESS")));
        when(entities.options(10L,null)).thenReturn(options);
        var day=new DictDataRespDTO();day.setStatus(0);day.setValue("1");day.setLabel("天（T+1）");
        var hour=new DictDataRespDTO();hour.setStatus(0);hour.setValue("2");hour.setLabel("小时（T+h）");
        when(dictionaries.getDictDataList("modeling_logic_data_timeliness")).thenReturn(CommonResult.success(List.of(day,hour)));
    }
    private BusinessEntityRespVO.Summary entity(long id,String name,String code,String type,String sub) {
        var row=new BusinessEntityRespVO.Summary();row.setId(id);row.setName(name);row.setCode(code);row.setEntityType(type);row.setEntitySubtype(sub);row.setSubjectDomainId(20L);row.setStatus(0);return row;
    }
    private RegistrationNameReqDTO request(String type) {
        var request=new RegistrationNameReqDTO();request.setDataDomainId(10L);request.setSubjectDomainId(20L);request.setTableType(type);
        request.setNamingCase("UPPER");request.setSourceTableName("ODS_RIVER");request.setSourceTableComment("河流维表");return request;
    }
    @Test void optionsUseRealIdsAndCustomLayersWithoutInferringMarketCategory() {
        var empty=service.context(null,"DIM",null);assertNull(empty.getSelectedDataDomainId());assertTrue(empty.getEntities().isEmpty());
        assertEquals(List.of(10L),empty.getDataDomains().stream().map(RegistrationContextRespDTO.Domain::id).toList());
        var context=service.context(10L,"DIM",20L);assertEquals(10L,context.getSelectedDataDomainId());assertEquals("EXPLICIT",context.getSelectionSource());
        assertEquals("LANDING",context.getLayers().getFirst().code());assertEquals(2,context.getEntities().size());assertEquals(2,context.getGranularities().size());
    }
    @Test void odsAvoidsDuplicatePrefixAndUsesAuthoritativeSourceComment() {
        var value=service.preview(request("ODS"));assertEquals("ODS_RIVER",value.getBusinessEnglishName());assertEquals("贴源逻辑表_河流维表",value.getAssetName());
    }
    @Test void odsAndAdsCanUseOfficialDefaultsWithoutAnyBoard() {
        var request=request("ODS");request.setDataDomainId(null);request.setSubjectDomainId(null);
        assertEquals("ODS_RIVER",service.preview(request).getBusinessEnglishName());
        request.setTableType("ADS");assertEquals("ADS_ODS_RIVER",service.preview(request).getBusinessEnglishName());
        request.setTableType("DIM");assertThrows(RuntimeException.class,()->service.preview(request));
    }
    @Test void persistedProcessRuleControlsPrefixThemeAndTimeSuffix() {
        var rules=DataDomainNamingRuleVO.detailDefaults();var process=rules.stream().filter(row->row.getTableType().equals("FACT_PROCESS")).findFirst().orElseThrow();
        process.setPrefix("custom_");process.setHourSuffix("_live");process.setIncludeSubjectAbbreviation(true);board.setNamingRules(JsonUtils.toJsonString(rules));
        var input=request("DWD");input.setEntityId(41L);input.setTimeEffect("2");input.setNamingCase("LOWER");
        var result=service.preview(input);assertEquals("custom_rh_flow_live",result.getBusinessEnglishName());assertEquals("FACT_PROCESS",result.getRuleType());
        assertEquals("事实逻辑表_河道水情_调度流程_小时（T+h）",result.getAssetName());
    }
    @Test void dwsConcatenatesRealGrainCodesInSelectionOrder() {
        var input=request("DWS");input.setGranularityEntityIds(List.of(32L,30L));input.setTimeEffect("2");
        var result=service.preview(input);assertEquals("DWS_RH_REGST_HH",result.getBusinessEnglishName());
        assertEquals("汇总逻辑表_河道水情_区域_测站_小时（T+h）",result.getAssetName());
    }
    @Test void foreignOrWrongKindEntityAndDuplicateGrainsAreRejected() {
        var input=request("DIM");input.setEntityId(40L);assertThrows(RuntimeException.class,()->service.preview(input));
        input.setEntityId(999L);assertThrows(RuntimeException.class,()->service.preview(input));
        input.setTableType("DWS");input.setGranularityEntityIds(List.of(30L,30L));assertThrows(RuntimeException.class,()->service.preview(input));
        input.setGranularityEntityIds(List.of(40L));assertThrows(RuntimeException.class,()->service.preview(input));
    }
    @Test void disabledBoardAndForeignSubjectUseExistingHierarchyValidation() {
        board.setStatus(1);assertThrows(RuntimeException.class,()->service.context(10L,"ODS",null));board.setStatus(0);
        doThrow(new IllegalArgumentException("foreign subject")).when(hierarchy).validateSubjectDomain(999L,null,10L);
        assertThrows(IllegalArgumentException.class,()->service.context(10L,"DIM",999L));
    }
}
