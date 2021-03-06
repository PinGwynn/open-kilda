package org.openkilda.atdd.staging.service.traffexam.model;

import java.util.InputMismatchException;

public class Exam {
    private final Host source;
    private final Host dest;
    private Vlan vlan;
    private Bandwidth bandwidthLimit;
    private TimeLimit timeLimitSeconds;

    private ExamResources resources;

    public Exam(Host source, Host dest) {
        this.source = source;
        this.dest = dest;
    }

    public Host getSource() {
        return source;
    }

    public Host getDest() {
        return dest;
    }

    public Vlan getVlan() {
        return vlan;
    }

    public Exam withVlan(Vlan vlan) {
        fieldRewriteCheck(this.vlan);
        this.vlan = vlan;
        return this;
    }

    public Bandwidth getBandwidthLimit() {
        return bandwidthLimit;
    }

    public Exam withBandwidthLimit(Bandwidth bandwidthLimit) {
        fieldRewriteCheck(this.bandwidthLimit);
        this.bandwidthLimit = bandwidthLimit;
        return this;
    }

    public TimeLimit getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    public Exam withTimeLimitSeconds(TimeLimit timeLimitSeconds) {
        fieldRewriteCheck(this.timeLimitSeconds);
        this.timeLimitSeconds = timeLimitSeconds;
        return this;
    }

    public ExamResources getResources() {
        return resources;
    }

    public void setResources(ExamResources resources) {
        fieldRewriteCheck(this.resources);
        this.resources = resources;
    }

    private void fieldRewriteCheck(Object ref) {
        if (ref != null) {
            throw new InputMismatchException("Rewrite attempt for write once field");
        }
    }
}
