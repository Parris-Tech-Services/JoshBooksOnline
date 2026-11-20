package au.com.parristech.ruizusync.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** JUnit entry point so `gradle test` runs the full JVM suites. */
public class LogicGradleTests {

    @Test
    public void coreLogicChecks() throws Exception {
        assertEquals("core logic check failures", 0, CoreTests.runAll());
    }

    @Test
    public void transferPipelineChecks() throws Exception {
        assertEquals("transfer pipeline check failures", 0, PipelineTests.runAll());
    }
}
