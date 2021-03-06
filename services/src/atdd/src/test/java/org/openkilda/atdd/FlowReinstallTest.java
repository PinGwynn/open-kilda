package org.openkilda.atdd;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertTrue;

import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import org.apache.commons.lang.StringUtils;
import org.awaitility.Duration;
import org.openkilda.SwitchesUtils;
import org.openkilda.flow.FlowUtils;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.messaging.payload.flow.FlowPathPayload;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class FlowReinstallTest {

    @When("^switch (.*) is turned off$")
    public void turnOffSwitch(String switchName) throws InterruptedException {
        TimeUnit.SECONDS.sleep(1);
        assertTrue("Switch should be turned off", SwitchesUtils.knockoutSwitch(switchName));
    }

    @When("^switch (.*) is turned on")
    public void turnOnSwitch(String switchName) throws InterruptedException {
        TimeUnit.SECONDS.sleep(1);
        assertTrue("Switch should be turned on", SwitchesUtils.reviveSwitch(switchName));
    }

    @Then("^flow (.*) is(.*) built through (.*) switch")
    public void flowPathContainsSwitch(final String flow, final String shouldNotContain, final String switchId)
            throws InterruptedException {
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(Duration.ONE_SECOND)
                .until(() -> {
            FlowPathPayload payload = FlowUtils.getFlowPath(FlowUtils.getFlowName(flow));
            assertTrue("Flow path should exist", payload != null && payload.getPath() != null);
            List<PathNode> path = payload.getPath().getPath();
            boolean contains = path.stream()
                    .anyMatch(node -> switchId.equalsIgnoreCase(node.getSwitchId()));

            if (StringUtils.isBlank(shouldNotContain)) {
                return contains;
            } else {
                return !contains;
            }
        });
    }
}
