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
    private Map<String, Integer> relativeCoordToLinearAddress;
    private Map<Integer, int[]> linearAddressToCoord;
    private Map<Integer, int[]> callSiteBindings;
    private Map<String, Integer> registerAliasMap;
    private Map<String, List<String>> procNameToParamNames;
    private Map<SerializableSourceInfo, SerializableTokenInfo> tokenMap;
    private Map<String, Map<Integer, Map<Integer, List<SerializableTokenInfo>>>> tokenLookup;

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

    public Map<String, Integer> getRelativeCoordToLinearAddress() {
        return relativeCoordToLinearAddress;
    }

    public void setRelativeCoordToLinearAddress(Map<String, Integer> relativeCoordToLinearAddress) {
        this.relativeCoordToLinearAddress = relativeCoordToLinearAddress;
    }

    public Map<Integer, int[]> getCallSiteBindings() {
        return callSiteBindings;
    }

    public void setCallSiteBindings(Map<Integer, int[]> callSiteBindings) {
        this.callSiteBindings = callSiteBindings;
    }

    public Map<String, Integer> getRegisterAliasMap() {
        return registerAliasMap;
    }

    public void setRegisterAliasMap(Map<String, Integer> registerAliasMap) {
        this.registerAliasMap = registerAliasMap;
    }

    public Map<Integer, int[]> getLinearAddressToCoord() {
        return linearAddressToCoord;
    }

    public void setLinearAddressToCoord(Map<Integer, int[]> linearAddressToCoord) {
        this.linearAddressToCoord = linearAddressToCoord;
    }

    public Map<String, List<String>> getProcNameToParamNames() {
        return procNameToParamNames;
    }

    public void setProcNameToParamNames(Map<String, List<String>> procNameToParamNames) {
        this.procNameToParamNames = procNameToParamNames;
    }

    public Map<SerializableSourceInfo, SerializableTokenInfo> getTokenMap() {
        return tokenMap;
    }

    public void setTokenMap(Map<SerializableSourceInfo, SerializableTokenInfo> tokenMap) {
        this.tokenMap = tokenMap;
    }

    public Map<String, Map<Integer, Map<Integer, List<SerializableTokenInfo>>>> getTokenLookup() {
        return tokenLookup;
    }

    public void setTokenLookup(Map<String, Map<Integer, Map<Integer, List<SerializableTokenInfo>>>> tokenLookup) {
        this.tokenLookup = tokenLookup;
    }
}
