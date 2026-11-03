package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlExecutionControlTest {
    @Test void cancellationBeforeRegistrationStopsLateResourcesAndNeverPretendsTheyExited() {
        var control = new EtlExecutionControl(); control.requestStop();
        AtomicBoolean exited = new AtomicBoolean(); AtomicInteger stops = new AtomicInteger(), exits = new AtomicInteger();
        control.register(stops::incrementAndGet, exited::get); control.whenResourcesExited(exits::incrementAndGet);
        assertEquals(1, stops.get()); assertFalse(control.resourcesExited()); assertEquals(0, exits.get());
        exited.set(true); assertTrue(control.resourcesExited()); assertEquals(1, exits.get());
        control.resourcesExited(); assertEquals(1, exits.get());
    }
    @Test void failedExitProbeCannotProduceConfirmedReceipt() {
        var control = new EtlExecutionControl(); control.register(() -> { throw new IllegalStateException(); }, () -> { throw new IllegalStateException(); });
        assertDoesNotThrow(control::requestStop); assertFalse(control.resourcesExited());
    }
}
