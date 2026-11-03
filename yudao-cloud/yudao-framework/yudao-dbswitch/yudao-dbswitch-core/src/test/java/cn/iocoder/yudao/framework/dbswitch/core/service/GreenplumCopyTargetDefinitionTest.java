package cn.iocoder.yudao.framework.dbswitch.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GreenplumCopyTargetDefinitionTest {
    @Test void retainsQuotedDistributionAndChecksOriginalIndexAttributes() {
        String create="CREATE TABLE s.t(\"a\"\"b\" bigint PRIMARY KEY,payload numeric(24,6) DEFAULT 1, note varchar) DISTRIBUTED BY(\"a\"\"b\")";
        assertDoesNotThrow(()->validate(create,"COMMENT ON COLUMN s.t.note IS 'DISTRIBUTED BY (unrelated)'",
                "CREATE INDEX ix ON s.t(note)"));
        assertThrows(IllegalArgumentException.class,()->validate(create,"CREATE INDEX ix ON s.t(note COLLATE \"C\")"));
        assertThrows(IllegalArgumentException.class,()->validate(create,"CREATE UNIQUE INDEX ix ON s.t(note)"));
        assertThrows(IllegalArgumentException.class,()->PostgresCopyTargetDefinition.validate("s","t",List.of(create),List.of("a\"b")));
    }
    @ParameterizedTest @ValueSource(strings={"uuid","jsonb","integer[]","timestamptz","timestamp(6) with time zone",
            "numeric(5,6)","numeric(5,-1)","varchar(0)","varchar(10485761)","time(7)","timestamp(7)",
            "bigserial","bigint GENERATED ALWAYS AS IDENTITY","bigint DEFAULT nextval('s.seq')"})
    void rejectsCopyEncodingsAndServerTypmodsBeforeAnyExecution(String type) {
        assertThrows(IllegalArgumentException.class,()->validate("CREATE TABLE s.t(\"a\"\"b\" bigint,payload "+type+") DISTRIBUTED BY(\"a\"\"b\")"));
    }
    @ParameterizedTest @ValueSource(strings={"", " DISTRIBUTED RANDOMLY", " DISTRIBUTED REPLICATED", " DISTRIBUTED BY(missing)",
            " DISTRIBUTED BY(payload)", " DISTRIBUTED BY(\"a\"\"b\"); DROP TABLE s.t"})
    void rejectsMissingIncompatibleOrAdditionalPlanActions(String suffix) {
        assertThrows(IllegalArgumentException.class,()->validate("CREATE TABLE s.t(\"a\"\"b\" bigint PRIMARY KEY,payload bigint)"+suffix));
    }
    private static void validate(String... statements) {
        PostgresCopyTargetDefinition.validateGreenplum("s","t",List.of(statements),List.of("a\"b","payload"));
    }
}
