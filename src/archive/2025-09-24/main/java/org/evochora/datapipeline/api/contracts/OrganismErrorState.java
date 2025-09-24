package org.evochora.datapipeline.api.contracts;

import java.util.List;

/**
 * Contains detailed information about a terminal error that occurred in an organism.
 */
public class OrganismErrorState {

    private String reason;
    private List<SerializableProcFrame> callStackAtFailure;

    /**
     * Default constructor for deserialization.
     */
    public OrganismErrorState() {
    }

    // Getters and setters

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<SerializableProcFrame> getCallStackAtFailure() {
        return callStackAtFailure;
    }

    public void setCallStackAtFailure(List<SerializableProcFrame> callStackAtFailure) {
        this.callStackAtFailure = callStackAtFailure;
    }

    @Override
    public String toString() {
        return "OrganismErrorState{" +
                "reason='" + reason + '\'' +
                ", callStackAtFailure=" + callStackAtFailure +
                '}';
    }
}
