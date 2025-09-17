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
    private List<InstructionMapping> machineCodeLayout;
    private List<PlacedMoleculeMapping> initialWorldObjects;
    private List<SourceMapEntry> sourceMap;
    private List<LabelMapping> labelMap;

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

    public List<InstructionMapping> getMachineCodeLayout() {
        return machineCodeLayout;
    }

    public void setMachineCodeLayout(List<InstructionMapping> machineCodeLayout) {
        this.machineCodeLayout = machineCodeLayout;
    }

    public List<PlacedMoleculeMapping> getInitialWorldObjects() {
        return initialWorldObjects;
    }

    public void setInitialWorldObjects(List<PlacedMoleculeMapping> initialWorldObjects) {
        this.initialWorldObjects = initialWorldObjects;
    }

    public List<SourceMapEntry> getSourceMap() {
        return sourceMap;
    }

    public void setSourceMap(List<SourceMapEntry> sourceMap) {
        this.sourceMap = sourceMap;
    }

    public List<LabelMapping> getLabelMap() {
        return labelMap;
    }

    public void setLabelMap(List<LabelMapping> labelMap) {
        this.labelMap = labelMap;
    }
}
