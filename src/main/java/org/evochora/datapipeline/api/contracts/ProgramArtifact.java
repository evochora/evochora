package org.evochora.datapipeline.api.contracts;

import java.util.List;
import java.util.Map;

/**
 * A rich, serializable representation of a compiled program, including all necessary metadata for
 * execution, analysis, and debugging.
 */
public class ProgramArtifact {

    private String programId;
    private Map<String, List<String>> sources;
    private Map<int[], Integer> machineCodeLayout;
    private Map<int[], SerializablePlacedMolecule> initialWorldObjects;
    private Map<Integer, SerializableSourceInfo> sourceMap;
    private Map<Integer, String> labelAddressToName;

    /**
     * Default constructor for deserialization.
     */
    public ProgramArtifact() {
    }

    // Getters and setters for all fields

    public String getProgramId() {
        return programId;
    }

    public void setProgramId(String programId) {
        this.programId = programId;
    }

    public Map<String, List<String>> getSources() {
        return sources;
    }

    public void setSources(Map<String, List<String>> sources) {
        this.sources = sources;
    }

    public Map<int[], Integer> getMachineCodeLayout() {
        return machineCodeLayout;
    }

    public void setMachineCodeLayout(Map<int[], Integer> machineCodeLayout) {
        this.machineCodeLayout = machineCodeLayout;
    }

    public Map<int[], SerializablePlacedMolecule> getInitialWorldObjects() {
        return initialWorldObjects;
    }

    public void setInitialWorldObjects(Map<int[], SerializablePlacedMolecule> initialWorldObjects) {
        this.initialWorldObjects = initialWorldObjects;
    }

    public Map<Integer, SerializableSourceInfo> getSourceMap() {
        return sourceMap;
    }

    public void setSourceMap(Map<Integer, SerializableSourceInfo> sourceMap) {
        this.sourceMap = sourceMap;
    }

    public Map<Integer, String> getLabelAddressToName() {
        return labelAddressToName;
    }

    public void setLabelAddressToName(Map<Integer, String> labelAddressToName) {
        this.labelAddressToName = labelAddressToName;
    }
}
