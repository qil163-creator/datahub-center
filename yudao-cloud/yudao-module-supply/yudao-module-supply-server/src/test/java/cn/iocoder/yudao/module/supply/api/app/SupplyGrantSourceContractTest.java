package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Action;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Binding;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Scope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SupplyGrantSourceContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();

    @Test
    void purposeIsPartOfCanonicalPayload() {
        var command = grant(scope("核验", List.of("id")));
        assertThat(SupplyGrantCommandCodec.hash( command))
                .isNotEqualTo(SupplyGrantCommandCodec.hash( grant(scope("统计", List.of("id")))))
                .isEqualTo(SupplyGrantCommandCodec.hash(command))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void fieldSetIsCanonicalAndImmutableButPhysicalCaseIsSignificant() {
        var columns = new ArrayList<>(List.of("Name", "id", "id"));
        var scope = scope("核验", columns);
        columns.clear();
        assertThat(scope.approvedColumns()).containsExactly("Name", "id");
        assertThatThrownBy(() -> scope.approvedColumns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(hash(scope)).isEqualTo(hash(scope(" 核验 ", List.of("id", "Name"))))
                .isNotEqualTo(hash(scope("核验", List.of("id", "name"))));
    }

    @Test
    void everyScopeDimensionChangesIdentity() {
        var s = scope("核验", List.of("id"));
        List<Scope> changes = List.of(
                new Scope("apply:8:r1", s.sourceType(), s.permissionUserId(), s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), "DEPARTMENT", s.permissionUserId(), s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), 10L, s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), new Binding(4L, "public", "citizen"), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), new Binding(3L, "private", "citizen"), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), new Binding(3L, "public", "Citizen"), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(), "contract-2", s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(), s.approvedContractHash(), "v2", s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), "事项2", s.policyMode(), s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), "OTHER_POLICY", s.approvedColumns(), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), List.of("name"), false, 1000L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1001L, 2000L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, 2001L),
                new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(), s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), true, 1000L, null));
        assertThat(changes).allSatisfy(changed -> assertThat(hash(changed)).isNotEqualTo(hash(s)));
        assertThat(changes.stream().map(this::hash).distinct()).hasSize(changes.size());
    }

    @Test
    void operationIdIsNotPartOfPayloadButSourceRevisionIdentityAndActionAre() {
        var c = grant(scope("核验", List.of("id")));
        var samePayload = new SupplyGrantSourceCommandDTO(2, "op2", "asset", c.sourceKey(), 1, 1L, 2L, Action.GRANT, c.scope(), null);
        assertThat(SupplyGrantCommandCodec.hash( samePayload)).isEqualTo(SupplyGrantCommandCodec.hash( c));
        List<SupplyGrantSourceCommandDTO> changes = List.of(
                new SupplyGrantSourceCommandDTO(2, "op1", "other", c.sourceKey(), 1, 1L, 2L, Action.GRANT, c.scope(), null),
                new SupplyGrantSourceCommandDTO(2, "op1", "asset", "asset:apply:8", 1, 1L, 2L, Action.GRANT, c.scope(), null),
                new SupplyGrantSourceCommandDTO(2, "op1", "asset", c.sourceKey(), 2, 1L, 2L, Action.GRANT, c.scope(), null),
                new SupplyGrantSourceCommandDTO(2, "op1", "asset", c.sourceKey(), 1, 3L, 2L, Action.GRANT, c.scope(), null),
                new SupplyGrantSourceCommandDTO(2, "op1", "asset", c.sourceKey(), 1, 1L, 3L, Action.GRANT, c.scope(), null),
                new SupplyGrantSourceCommandDTO(2, "op1", "asset", c.sourceKey(), 1, 1L, 2L, Action.REVOKE, null, null));
        assertThat(changes).allSatisfy(changed -> assertThat(SupplyGrantCommandCodec.hash( changed))
                .isNotEqualTo(SupplyGrantCommandCodec.hash( c)));
    }

    @Test
    void binaryFramingPreventsDelimiterAmbiguity() {
        var a = new SupplyGrantSourceCommandDTO(2, "op1", "a:b", "c", 1, 1L, 2L, Action.REVOKE, null, null);
        var b = new SupplyGrantSourceCommandDTO(2, "op1", "a", "b:c", 1, 1L, 2L, Action.REVOKE, null, null);
        assertThat(SupplyGrantCommandCodec.hash( a)).isNotEqualTo(SupplyGrantCommandCodec.hash( b));
    }

    @Test
    void suspendAndRevokeNeedNoCurrentMetadata() {
        for (var action : List.of(Action.SUSPEND, Action.REVOKE)) {
            var command = new SupplyGrantSourceCommandDTO(2, "op", "asset", "source", 1, 1L, 2L, action, null, "停用");
            assertThat(mapper.readValue(mapper.writeValueAsString(command), SupplyGrantSourceCommandDTO.class)).isEqualTo(command);
        }
        assertThatThrownBy(() -> new SupplyGrantSourceCommandDTO(2, "op", "asset", "source", 1, 1L, 2L, Action.GRANT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingIdentityVersionActionOrRevision() {
        assertThatThrownBy(() -> new SupplyGrantSourceCommandDTO(0, "op", "asset", "s", 1, 1L, 2L, Action.REVOKE, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SupplyGrantSourceCommandDTO(2, " ", "asset", "s", 1, 1L, 2L, Action.REVOKE, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SupplyGrantSourceCommandDTO(2, "op", "asset", "s", 0, 1L, 2L, Action.REVOKE, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SupplyGrantSourceCommandDTO(2, "op", "asset", "s", 1, null, 2L, Action.REVOKE, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SupplyGrantSourceCommandDTO(2, "op", "asset", "s", 1, 1L, 2L, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SupplyGrantSourceCommandDTO(2, "op", "asset", "x".repeat(257), 1, 1L, 2L, Action.REVOKE, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyPhysicalFieldsAndUnprovenSchema() {
        for (var columns : List.of(List.<String>of(), List.of(" "), List.of("*"))) {
            assertThatThrownBy(() -> scope("核验", columns)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Binding(3L, null, "citizen")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsImplicitForeverAndInvalidIntervals() {
        var s = scope("核验", List.of("id"));
        for (Long end : new Long[]{null, 1000L, 999L}) {
            assertThatThrownBy(() -> new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(),
                    s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), false, 1000L, end))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Scope(s.approvalRef(), s.sourceType(), s.permissionUserId(), s.binding(),
                s.approvedContractHash(), s.apiVersion(), s.purpose(), s.dutyMatter(), s.policyMode(), s.approvedColumns(), true, 1000L, 2000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectMapperPreservesLargeIdsAndMillisWithoutLoss() {
        var command = new SupplyGrantSourceCommandDTO(2, "op", "asset", "source", Long.MAX_VALUE, Long.MAX_VALUE, 2L,
                Action.GRANT, scope("核验", List.of("id")), null);
        var json = mapper.writeValueAsString(command);
        var tree = mapper.readTree(json);
        assertThat(tree.get("appId").isTextual()).isTrue();
        assertThat(tree.get("appId").asString()).isEqualTo("9223372036854775807");
        assertThat(tree.get("apiId").isIntegralNumber()).isTrue();
        assertThat(tree.get("scope").get("validFromMillis").asLong()).isEqualTo(1000L);
        assertThat(mapper.readValue(json, SupplyGrantSourceCommandDTO.class)).isEqualTo(command);
        assertThatThrownBy(() -> mapper.readValue(json.replace("\"GRANT\"", "\"UNKNOWN\""), SupplyGrantSourceCommandDTO.class))
                .isInstanceOf(RuntimeException.class);
    }

    private String hash(Scope scope) {
        return SupplyGrantCommandCodec.hash( grant(scope));
    }

    private static Scope scope(String purpose, List<String> columns) {
        return new Scope("apply:7:r1", "PERSONAL", 9L, new Binding(3L, "public", "citizen"),
                "contract-1", "v1", purpose, "事项1", "CURRENT_USER_GOVERNANCE", columns, false, 1000L, 2000L);
    }

    private static SupplyGrantSourceCommandDTO grant(Scope scope) {
        return new SupplyGrantSourceCommandDTO(2, "op1", "asset", "asset:apply:7", 1, 1L, 2L, Action.GRANT, scope, null);
    }
}
