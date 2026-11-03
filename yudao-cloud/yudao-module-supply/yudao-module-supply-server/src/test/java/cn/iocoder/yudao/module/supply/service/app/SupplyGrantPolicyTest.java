package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.*;
import cn.iocoder.yudao.module.supply.service.app.grant.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static cn.iocoder.yudao.module.supply.service.app.grant.GrantSourceState.Status.*;

class SupplyGrantPolicyTest {
    @Test
    void compatibleSourcesUnionAndRevokingOnlyOneRetainsOthers() {
        var a = source("a", ACTIVE, scope(List.of("id", "name"), 1000L, 2000L, 9L, "核验"));
        var b = source("b", ACTIVE, scope(List.of("id", "phone"), 1000L, 3000L, 9L, "核验"));
        assertThat(evaluate(List.of(a, b), 1500).approvedColumns()).containsExactly("id", "name", "phone");
        var revoked = new GrantSourceState(a.sourceSystem(), a.sourceKey(), 2, 1, 2, REVOKED, a.scope(), "revoked");
        assertThat(evaluate(List.of(revoked, b), 1500).approvedColumns()).containsExactly("id", "phone");
        assertThat(evaluate(List.of(a, b), 2000).approvedColumns()).containsExactly("id", "phone");
    }

    @ParameterizedTest
    @ValueSource(longs = {999, 2000, 2500, 4000})
    void deniesBeforeAfterAndBetweenHalfOpenIntervals(long now) {
        var a = source("a", ACTIVE, scope(List.of("id"), 1000L, 2000L, 9L, "核验"));
        var b = source("b", ACTIVE, scope(List.of("phone"), 3000L, 4000L, 9L, "核验"));
        assertThatThrownBy(() -> evaluate(List.of(a, b), now)).isInstanceOf(SupplyGrantPolicy.Rejected.class);
    }

    @Test
    void nextBoundaryIncludesFutureStartAndImmutableSourceVersions() {
        var a = source("a", ACTIVE, scope(List.of("id"), 1000L, 4000L, 9L, "核验"));
        var b = source("b", ACTIVE, scope(List.of("phone"), 2000L, 3000L, 9L, "核验"));
        var snapshot = evaluate(List.of(a, b), 1500);
        assertThat(snapshot.nextBoundary()).isEqualTo(Instant.ofEpochMilli(2000));
        assertThat(snapshot.approvedColumns()).containsExactly("id");
        assertThatThrownBy(() -> snapshot.approvedColumns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.sourceVersions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void futureAndSuspendedIncompatibleSourcesStillBlockCombination() {
        var a = source("a", ACTIVE, scope(List.of("id"), 1000L, 4000L, 9L, "核验"));
        for (var status : List.of(ACTIVE, SUSPENDED)) {
            var b = source("b", status, scope(List.of("phone"), 3000L, 4000L, 10L, "核验"));
            assertThatThrownBy(() -> evaluate(List.of(a, b), 1500))
                    .isInstanceOfSatisfying(SupplyGrantPolicy.Rejected.class, ex -> assertThat(ex.reason()).isEqualTo(SupplyGrantPolicy.Reason.INCOMPATIBLE_SCOPE));
        }
    }

    @Test
    void purposeAndAdminModeAreNotMergedIntoPersonalScope() {
        var s = scope(List.of("id"), 1000L, 4000L, 9L, "核验");
        var a = source("a", ACTIVE, s);
        var differentPurpose = source("b", ACTIVE, scope(List.of("id"), 1000L, 4000L, 9L, "统计"));
        assertThatThrownBy(() -> evaluate(List.of(a, differentPurpose), 1500)).isInstanceOf(SupplyGrantPolicy.Rejected.class);
        var admin = new Scope(s.approvalRef(), "ADMIN", 9L, s.binding(), s.approvedContractHash(), s.apiVersion(),
                s.purpose(), s.dutyMatter(), "ADMIN", s.approvedColumns(), false, 1000L, 4000L);
        assertThatThrownBy(() -> evaluate(List.of(a, source("admin", ACTIVE, admin)), 1500)).isInstanceOf(SupplyGrantPolicy.Rejected.class);
    }

    @Test
    void suspendRetainsApprovalAndHigherGrantResumesButRevokeIsTerminal() {
        var grant = command("a", 1, Action.GRANT, scope(List.of("id"), 1000L, 4000L, 9L, "核验"));
        var active = SupplyGrantPolicy.transition( null, grant);
        var suspended = SupplyGrantPolicy.transition( active, command("a", 2, Action.SUSPEND, null));
        assertThat(suspended.status()).isEqualTo(SUSPENDED);
        assertThat(suspended.scope()).isEqualTo(grant.scope());
        var resumed = SupplyGrantPolicy.transition( suspended, command("a", 3, Action.GRANT, grant.scope()));
        assertThat(resumed.status()).isEqualTo(ACTIVE);
        var revoked = SupplyGrantPolicy.transition( resumed, command("a", 4, Action.REVOKE, null));
        assertThat(revoked.status()).isEqualTo(REVOKED);
        assertThatThrownBy(() -> SupplyGrantPolicy.transition( revoked, command("a", 5, Action.GRANT, grant.scope())))
                .isInstanceOfSatisfying(SupplyGrantPolicy.Rejected.class, ex -> assertThat(ex.reason()).isEqualTo(SupplyGrantPolicy.Reason.TERMINAL_SOURCE));
    }

    @Test
    void firstRevokeCreatesTombstoneWithoutScopeOrCurrentMetadata() {
        var revoked = SupplyGrantPolicy.transition( null, command("a", 2, Action.REVOKE, null));
        assertThat(revoked.status()).isEqualTo(REVOKED);
        assertThat(revoked.scope()).isNull();
        assertThatThrownBy(() -> SupplyGrantPolicy.transition( revoked, command("a", 1, Action.GRANT,
                scope(List.of("id"), 1000L, 4000L, 9L, "核验")))).isInstanceOf(SupplyGrantPolicy.Rejected.class);
    }

    @Test
    void sourceRevisionIsIdempotentAndCannotMoveBinding() {
        var grant = command("a", 1, Action.GRANT, scope(List.of("id"), 1000L, 4000L, 9L, "核验"));
        var current = SupplyGrantPolicy.transition( null, grant);
        assertThat(SupplyGrantPolicy.transition( current, grant)).isSameAs(current);
        assertThatThrownBy(() -> SupplyGrantPolicy.transition( current, command("a", 1, Action.REVOKE, null)))
                .isInstanceOfSatisfying(SupplyGrantPolicy.Rejected.class, ex -> assertThat(ex.reason()).isEqualTo(SupplyGrantPolicy.Reason.REVISION_CONFLICT));
        var moved = new SupplyGrantSourceCommandDTO(2, "move", "asset", "a", 2, 3L, 2L, Action.REVOKE, null, null);
        assertThatThrownBy(() -> SupplyGrantPolicy.transition( current, moved)).isInstanceOf(SupplyGrantPolicy.Rejected.class);
    }

    @Test
    void aggregateDisableAndUnknownLegacyEvidenceFailClosed() {
        var a = source("a", ACTIVE, scope(List.of("id"), 1000L, 4000L, 9L, "核验"));
        assertThatThrownBy(() -> SupplyGrantPolicy.evaluate( 1, 2, 1, true, List.of(a), Instant.ofEpochMilli(1500)))
                .isInstanceOf(SupplyGrantPolicy.Rejected.class);
        assertThatThrownBy(() -> evaluate(List.of(a, source("legacy", REVIEW_REQUIRED, null)), 1500))
                .isInstanceOf(SupplyGrantPolicy.Rejected.class);
    }

    @Test
    void compatibilityIgnoresApprovalReferenceFieldSetAndTimeButNotPhysicalBinding() {
        var a = scope(List.of("id"), 1000L, 2000L, 9L, "核验");
        var b = new Scope("other-approval", a.sourceType(), a.permissionUserId(), a.binding(), a.approvedContractHash(),
                a.apiVersion(), " 核验 ", a.dutyMatter(), a.policyMode(), List.of("phone"), false, 3000L, 4000L);
        assertThat(SupplyGrantPolicy.compatibilityKey(a)).isEqualTo(SupplyGrantPolicy.compatibilityKey(b));
        var changedSchema = new Scope(b.approvalRef(), b.sourceType(), b.permissionUserId(), new Binding(3L,"other","citizen"),
                b.approvedContractHash(), b.apiVersion(), b.purpose(), b.dutyMatter(), b.policyMode(), b.approvedColumns(), b.forever(), 3000L,4000L);
        assertThat(SupplyGrantPolicy.compatibilityKey(a)).isNotEqualTo(SupplyGrantPolicy.compatibilityKey(changedSchema));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CURRENT_USER_GOVERNANCE", "APPLICATION_GRANT"})
    void legacyModeDoesNotControlRuntimeGrantButRemainsInCompatibilityDigest(String mode) {
        var base = scope(List.of("id"), 1000L, 4000L, 9L, "核验");
        var selected = new Scope(base.approvalRef(), base.sourceType(), base.permissionUserId(), base.binding(),
                base.approvedContractHash(), base.apiVersion(), base.purpose(), base.dutyMatter(), mode,
                base.approvedColumns(), base.forever(), base.validFromMillis(), base.validToMillis());
        assertThat(evaluate(List.of(source("a", ACTIVE, selected)), 1500).approvedColumns()).containsExactly("id");
        if (!mode.equals(base.policyMode())) {
            assertThat(SupplyGrantPolicy.compatibilityKey(selected)).isNotEqualTo(SupplyGrantPolicy.compatibilityKey(base));
        }
    }

    private static EffectiveGrantSnapshot evaluate(List<GrantSourceState> sources, long now) {
        return SupplyGrantPolicy.evaluate( 1, 2, 1, false, sources, Instant.ofEpochMilli(now));
    }

    private static GrantSourceState source(String key, GrantSourceState.Status status, Scope scope) {
        return new GrantSourceState("asset", key, 1, 1, 2, status, scope, "hash");
    }

    private static Scope scope(List<String> fields, Long start, Long end, Long user, String purpose) {
        return new Scope("apply:7:r1", "PERSONAL", user, new Binding(3L, "public", "citizen"),
                "contract-1", "v1", purpose, "事项1", "CURRENT_USER_GOVERNANCE", fields, false, start, end);
    }

    private static SupplyGrantSourceCommandDTO command(String key, long revision, Action action, Scope scope) {
        return new SupplyGrantSourceCommandDTO(2, "op:" + revision, "asset", key, revision, 1L, 2L, action, scope, null);
    }
}
