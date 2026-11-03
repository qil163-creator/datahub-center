package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import jakarta.validation.ConstraintViolationException;
import cn.iocoder.yudao.module.modeling.api.registration.dto.RegistrationContextRespDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import java.util.List;
import static org.mockito.Mockito.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class AssetRegistrationConfigTest {
    @Test void rejectsInvalidEnumAndOversizedBusinessConfiguration() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var service = new AssetRegistrationConfig(); ReflectionTestUtils.setField(service, "validator", factory.getValidator());
            var config = new AssetRegistrationConfigVO(); config.setTableType("UNKNOWN");
            assertThatThrownBy(() -> service.serialize(config)).isInstanceOf(ConstraintViolationException.class);
            config.setTableType("ODS"); config.setBusinessEnglishName("x".repeat(257));
            assertThatThrownBy(() -> service.serialize(config)).isInstanceOf(ConstraintViolationException.class);
            config.setBusinessEnglishName("business_rainfall"); config.setTimeEffect("1");
            assertThat(JsonUtils.parseObject(service.serialize(config), AssetRegistrationConfigVO.class).getBusinessEnglishName()).isEqualTo("business_rainfall");
        }
    }
    @Test void registeringBusinessEnglishNameNeverRenamesPhysicalSourceTable() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var codec = new AssetRegistrationConfig(); ReflectionTestUtils.setField(codec, "validator", factory.getValidator());
            var service = new DataAssetServiceImpl(); ReflectionTestUtils.setField(service, "registrationConfig", codec);
            var req = new DataAssetRegisterReqVO(); var config = new AssetRegistrationConfigVO(); config.setBusinessEnglishName("BusinessRainfall"); req.setRegistrationConfig(config);
            var metadata = new MetadataTableRespDTO(); metadata.setId(7L); metadata.setTableName("physical_rainfall");
            DataAssetDO asset = ReflectionTestUtils.invokeMethod(service, "buildAsset", req, metadata);
            assertThat(asset).isNotNull(); assertThat(asset.getTableName()).isEqualTo("physical_rainfall");
            assertThat(asset.getRegistrationConfigJson()).contains("BusinessRainfall");
        }
    }
    @Test void validatesRealDomainMembershipAndResolvesLabelsInsteadOfTrustingBrowserText() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var contexts = mock(AssetRegistrationContextService.class); var codec = new AssetRegistrationConfig();
            ReflectionTestUtils.setField(codec, "validator", factory.getValidator()); ReflectionTestUtils.setField(codec, "contexts", contexts);
            var context = new RegistrationContextRespDTO(); context.setSelectedDataDomainId(7L);
            context.setDataDomains(List.of(new RegistrationContextRespDTO.Domain(7L,"真实板块","WATER")));
            context.setSubjectDomains(List.of(new RegistrationContextRespDTO.Subject(71L,null,7L,"真实主题","RIVER","RV")));
            context.setEntities(List.of(new RegistrationContextRespDTO.Entity(100L,"真实实体","STATION",71L,"1","1")));
            context.setGranularities(List.of(new RegistrationContextRespDTO.Entity(200L,"真实粒度","DAY",71L,"1","1")));
            context.setLayers(List.of(new RegistrationContextRespDTO.Layer(8L,"自定义层","CUSTOM_LAYER","custom_","DIM")));
            when(contexts.context(any(),any(),any())).thenReturn(context);
            var input = new AssetRegistrationConfigVO(); input.setDataDomainId(7L); input.setDataDomainName("伪造名称"); input.setTableType("DIM"); input.setEntityId(100L); input.setBusinessEntity("伪造实体"); input.setGranularityEntityIds(List.of(200L)); input.setStatGranularity("伪造粒度"); input.setLayer("CUSTOM_LAYER");
            var resolved = codec.resolve(input,71L); var stored = JsonUtils.parseObject(resolved.json(),AssetRegistrationConfigVO.class);
            assertThat(stored.getDataDomainName()).isEqualTo("真实板块"); assertThat(stored.getBusinessEntity()).isEqualTo("真实实体"); assertThat(stored.getStatGranularity()).isEqualTo("真实粒度"); assertThat(resolved.subjectDomainName()).isEqualTo("真实主题");
            input.setEntityId(101L); assertThatThrownBy(()->codec.resolve(input,71L)).isInstanceOf(ServiceException.class);
            input.setEntityId(100L); assertThatThrownBy(()->codec.resolve(input,72L)).isInstanceOf(ServiceException.class);
            input.setLayer("TYPO"); assertThatThrownBy(()->codec.resolve(input,71L)).isInstanceOf(ServiceException.class);
        }
    }

}
