package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static cn.iocoder.yudao.module.dataforge.service.execution.DistributedRunFaultSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Local control-plane scale check, NOT a throughput claim for actual ETL engines. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = ".+")
@Timeout(90)
class RunCapacityScalePostgreSqlTest {
    private DistributedRunFaultSupport fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new DistributedRunFaultSupport(System.getProperty("datahub.test.postgres.url"));
    }

    @AfterEach void cleanup() throws Exception { if (fixture != null) fixture.close(); }

    @ParameterizedTest @EnumSource(Domain.class)
    void largeHotSourceDoesNotStarveOtherSources(Domain domain) {
        // Synthetic IDs live exclusively in the owned test schema. No task engine is invoked.
        String columns = "(id," + domain.taskColumn
                + ",protocol_version,run_status,execution_stage,resource_ids,create_time,"
                + "run_key,request_hash,config_json,execution_user_id,schedule_generation,effect_state)";
        String identity = ",repeat('a',64),'{}'::jsonb,99,1,'NONE'";
        fixture.db.execute("INSERT INTO " + domain.runs + columns
                + " SELECT n,n,1,1,'PREPARE','[10]'::jsonb,clock_timestamp()-interval '1 hour','hot:'||n" + identity
                + " FROM generate_series(1,20000) n");
        fixture.db.execute("INSERT INTO " + domain.runs + columns
                + " SELECT 20000+n,20000+n,1,1,'PREPARE','[20]'::jsonb,clock_timestamp(),'other:'||n" + identity
                + " FROM generate_series(1,12) n");
        fixture.db.execute("INSERT INTO " + domain.runs + columns
                + " VALUES (20013,20013,1,1,'PREPARE','[30]'::jsonb,clock_timestamp(),'cold'" + identity + ")");
        fixture.db.execute("INSERT INTO " + domain.runs + columns
                + " SELECT -n,n,1,3,'PREPARE','[10]'::jsonb,clock_timestamp()-interval '1 year','history:'||n" + identity
                + " FROM generate_series(1,50000) n");
        fixture.db.execute("ANALYZE " + domain.runs);

        var durations = new ArrayList<Double>();
        for (int pass = 0; pass < 5; pass++) {
            long started = System.nanoTime();
            List<Candidate> candidates = scan(domain, 14);
            durations.add((System.nanoTime() - started) / 1_000_000.0);
            assertThat(candidates).hasSize(14);
            assertThat(candidates.stream().map(Candidate::id).toList()).contains(20001L);
            assertThat(candidates.stream().map(Candidate::id).toList()).contains(20013L);
            assertThat(candidates.stream().filter(c -> c.id <= 20000)).isNotEmpty();
        }

        // UNKNOWN retains shared capacity even if its row is soft-deleted or held by the other domain.
        Domain other = domain == Domain.ETL ? Domain.ACCESS : Domain.ETL;
        fixture.db.execute("INSERT INTO " + other.runs + "(id," + other.taskColumn
                + ",run_status,occupies_slot,deleted) SELECT n,n,"
                + other.unknown + ",true,1 FROM generate_series(1,32) n");
        assertThat(scan(domain, 14)).isEmpty();
        durations.sort(Double::compareTo);
        System.out.printf("RunCapacityScale domain=%s queued=20013 historical=50000 scans=5 median_ms=%.2f max_ms=%.2f%n",
                domain, durations.get(2), durations.getLast());
    }

    private List<Candidate> scan(Domain domain, int limit) {
        return domain == Domain.ETL
                ? fixture.runtime.records.selectDispatchCandidates(limit, 32, 8).stream().map(this::candidate).toList()
                : fixture.runtime.accessRuns.waitingCandidates(limit, 32, 8).stream().map(this::candidate).toList();
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void saturatedSourceAcrossDifferentResourceSetsCannotHideColdSource(Domain domain) {
        String columns = "(id," + domain.taskColumn
                + ",protocol_version,run_status,execution_stage,resource_ids,create_time,"
                + "run_key,request_hash,config_json,execution_user_id,schedule_generation,effect_state)";
        String identity = ",repeat('a',64),'{}'::jsonb,99,1,'NONE'";
        fixture.db.execute("INSERT INTO " + domain.runs + columns
                + " SELECT n,n,1,1,'PREPARE',jsonb_build_array(10,1000+n),clock_timestamp()-interval '1 hour'"
                + ",'hot:'||n" + identity
                + " FROM generate_series(1,2000) n");
        fixture.db.execute("INSERT INTO " + domain.runs + columns + " VALUES"
                + " (2001,2001,1,1,'PREPARE','[30]',clock_timestamp(),'cold'" + identity + "),"
                + " (2002,2002,1,1,'PREPARE','[10]',clock_timestamp(),'other'" + identity + ")");
        Domain other = domain == Domain.ETL ? Domain.ACCESS : Domain.ETL;
        fixture.db.execute("INSERT INTO " + other.runs + "(id," + other.taskColumn
                + ",run_status,occupies_slot,deleted,resource_ids) SELECT n,n,"
                + other.unknown + ",true,1,'[10]'::jsonb FROM generate_series(1,8) n");

        for (int pass = 0; pass < 5; pass++) {
            assertThat(scan(domain, 14)).containsExactlyInAnyOrder(new Candidate(2001));
        }
        assertThat(fixture.db.queryForObject("SELECT count(*) FROM " + domain.runs + " WHERE run_status=1", Long.class))
                .isEqualTo(2002L); // Scanning only skips a saturated resource; it never drops accepted runs.
        fixture.db.update("UPDATE " + other.runs + " SET occupies_slot=false WHERE id=1");
        assertThat(scan(domain, 14)).hasSize(14).anyMatch(c -> c.id <= 2000);
    }
    private Candidate candidate(EtlRecordDO row) { return new Candidate(row.getId()); }

    @ParameterizedTest @EnumSource(Domain.class)
    void sourceHintsMatchUnknownOccupancyAndNeverTreatLegacyScopeAsEmpty(Domain domain) {
        fixture.db.execute("INSERT INTO " + domain.runs + "(id," + domain.taskColumn
                + ",protocol_version,run_status,execution_stage,resource_ids,create_time,"
                + "run_key,request_hash,config_json,execution_user_id,schedule_generation,effect_state)"
                + " SELECT n,n,1,1,'PREPARE',CASE n WHEN 1 THEN '[10,20]'::jsonb WHEN 2 THEN '[30]'::jsonb"
                + " WHEN 3 THEN '[]'::jsonb ELSE NULL END,clock_timestamp(),'scope:'||n,repeat('a',64),'{}',99,1,'NONE'"
                + " FROM generate_series(1,4) n");
        Domain other = domain == Domain.ETL ? Domain.ACCESS : Domain.ETL;
        fixture.db.execute("INSERT INTO " + other.runs + "(id," + other.taskColumn
                + ",run_status,occupies_slot,deleted,resource_ids) SELECT n,n,"
                + other.unknown + ",true,1,CASE WHEN n=8 THEN NULL ELSE '[20]'::jsonb END FROM generate_series(1,8) n");
        assertThat(scan(domain, 14)).containsExactlyInAnyOrder(new Candidate(2), new Candidate(3), new Candidate(4));
        // Missing waiting scope is resolved by the transactional claim, not assumed resource-free by this hint.
        fixture.db.update("UPDATE " + other.runs + " SET resource_ids=NULL");
        assertThat(scan(domain, 14)).containsExactlyInAnyOrder(new Candidate(3), new Candidate(4));
        fixture.db.update("UPDATE " + other.runs + " SET occupies_slot=false");
        assertThat(scan(domain, 14)).hasSize(4);
    }
    private Candidate candidate(DataAccessTaskDO row) { return new Candidate(row.getId()); }
    private record Candidate(long id) { }
}
