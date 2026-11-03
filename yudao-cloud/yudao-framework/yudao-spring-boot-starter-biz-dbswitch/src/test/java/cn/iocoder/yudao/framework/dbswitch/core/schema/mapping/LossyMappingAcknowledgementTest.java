package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

class LossyMappingAcknowledgementTest {
    private static final LogicalTypeMappings MAPPINGS = LogicalTypeMappings.installed();

    @Test void aDecisionRequiresTheExactTargetProductTableColumnAndPreview() {
        var mapping = MAPPINGS.map(SQLITE3, LogicalTypeMappingTest.pg("v", "bigint", Types.BIGINT), false);
        var acknowledgement = LossyMappingAcknowledgement.forColumn("main", "copy", mapping);
        var decisions = List.of(acknowledgement);
        assertTrue(LossyMappingAcknowledgement.acceptanceFor(decisions, SQLITE3, "main", "copy").accepts(mapping));
        assertFalse(LossyMappingAcknowledgement.acceptanceFor(decisions, SQLITE3, "main", "other").accepts(mapping));
        assertFalse(LossyMappingAcknowledgement.acceptanceFor(decisions, SQLITE3, "MAIN", "copy").accepts(mapping));
        assertFalse(LossyMappingAcknowledgement.acceptanceFor(decisions, POSTGRESQL, "main", "copy").accepts(mapping));
        var changed = MAPPINGS.map(SQLITE3, LogicalTypeMappingTest.pg("v", "integer", Types.INTEGER), false);
        assertFalse(LossyMappingAcknowledgement.acceptanceFor(decisions, SQLITE3, "main", "copy").accepts(changed));
        assertNotEquals(mapping.fingerprint(), changed.fingerprint());
    }

    @Test void aNewLossOrNullableDefinitionInvalidatesThePreviousDecision() {
        var mapping = MAPPINGS.map(SQLITE3, LogicalTypeMappingTest.pg("v", "bigint", Types.BIGINT), false);
        var policy = LossyMappingAcknowledgement.acceptanceFor(
                List.of(LossyMappingAcknowledgement.forColumn("main", "copy", mapping)), SQLITE3, "main", "copy");
        var issues = new ArrayList<>(mapping.issues());
        issues.add(TypeMappingIssue.loss("NEW_LOSS", "a new mapping requirement"));
        assertFalse(policy.accepts(new ColumnTypeMapping(mapping.column(), mapping.sourceProduct(), mapping.sourceNativeType(),
                mapping.logicalType(), mapping.targetProduct(), mapping.targetNativeType(), TypeFidelity.LOSSY, issues, mapping.definition())));
        assertFalse(policy.accepts(new ColumnTypeMapping(mapping.column(), mapping.sourceProduct(), mapping.sourceNativeType(),
                mapping.logicalType(), mapping.targetProduct(), mapping.targetNativeType(), TypeFidelity.LOSSY,
                mapping.issues(), mapping.definition() + " NOT NULL")));
    }

    @Test void exactAndUnsupportedColumnsCannotBeAcknowledgedOrAccepted() {
        var exact = MAPPINGS.map(POSTGRESQL, Sqlite3MappingFixtures.column("v", "INTEGER"), false);
        var rejected = MAPPINGS.map(POSTGRESQL, Sqlite3MappingFixtures.column("v", "DECIMAL(12,2)"), false);
        var policy = LossyMappingAcknowledgement.acceptanceFor(
                List.of(LossyMappingAcknowledgement.forColumn("public", "copy", exact)), POSTGRESQL, "public", "copy");
        assertFalse(policy.accepts(rejected));
        assertThrows(IllegalArgumentException.class, () -> LossyMappingAcknowledgement.forColumn("public", "copy", rejected));
        var mapped = MAPPINGS.map(MYSQL, LogicalTypeMappingTest.pg("v", "bigint", Types.BIGINT), false);
        assertEquals(TypeFidelity.EXACT, mapped.fidelity());
        assertThrows(IllegalArgumentException.class, () -> LossyMappingAcknowledgement.forColumn("public", "copy", mapped));
    }

    @Test void snapshotsFreezeTheDecisionCollectionAndLossCodes() {
        var mapping = MAPPINGS.map(SQLITE3, LogicalTypeMappingTest.pg("v", "bigint", Types.BIGINT), false);
        var mutableCodes = new HashSet<>(mapping.lossCodes());
        var decision = new LossyMappingAcknowledgement(SQLITE3, "main", "copy", "v", mapping.fingerprint(), mutableCodes);
        var request = new MigrationRequest();
        var mutable = new ArrayList<>(List.of(decision));
        request.setTypeMappingAcknowledgements(mutable);
        var captured = request.snapshot();
        mutable.clear(); mutableCodes.clear();
        assertEquals(List.of(decision), captured.getTypeMappingAcknowledgements());
        assertEquals(mapping.lossCodes(), decision.lossCodes());
        assertThrows(UnsupportedOperationException.class, () -> captured.getTypeMappingAcknowledgements().clear());
        assertThrows(UnsupportedOperationException.class, () -> decision.lossCodes().clear());
    }

    @Test void duplicateOrMalformedDecisionsFailBeforeMigrationStarts() {
        var mapping = MAPPINGS.map(SQLITE3, LogicalTypeMappingTest.pg("v", "bigint", Types.BIGINT), false);
        var decision = LossyMappingAcknowledgement.forColumn("main", "copy", mapping);
        var request = new MigrationRequest(); request.setTypeMappingAcknowledgements(List.of(decision, decision));
        assertThrows(IllegalArgumentException.class, request::snapshot);
        assertThrows(IllegalArgumentException.class, () -> new LossyMappingAcknowledgement(UNKNOWN, "main", "copy", "v", mapping.fingerprint(), Set.of("LOSS")));
        assertThrows(IllegalArgumentException.class, () -> new LossyMappingAcknowledgement(SQLITE3, "main", "copy", "v", "no-preview", Set.of("LOSS")));
        assertThrows(IllegalArgumentException.class, () -> new LossyMappingAcknowledgement(SQLITE3, "main", "copy", "v", mapping.fingerprint(), Set.of()));
    }

    @Test void diagnosticOrderDoesNotChangeTheMappingIdentity() {
        var mapping = MAPPINGS.map(SQLITE3, LogicalTypeMappingTest.pg("v", "bigint", Types.BIGINT), false);
        var issues = new ArrayList<>(mapping.issues()); java.util.Collections.reverse(issues);
        var reordered = new ColumnTypeMapping(mapping.column(), mapping.sourceProduct(), mapping.sourceNativeType(),
                mapping.logicalType(), mapping.targetProduct(), mapping.targetNativeType(), mapping.fidelity(), issues, mapping.definition());
        assertEquals(mapping.fingerprint(), reordered.fingerprint());
    }
}
