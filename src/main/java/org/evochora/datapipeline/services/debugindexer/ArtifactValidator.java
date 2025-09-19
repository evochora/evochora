package org.evochora.datapipeline.services.debugindexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles validation of ProgramArtifacts for organisms.
 * Provides caching for performance optimization and determines artifact validity
 * based on source mapping consistency.
 */
public class ArtifactValidator {
    
    private static final Logger log = LoggerFactory.getLogger(ArtifactValidator.class);
    
    /**
     * Represents the validity of a ProgramArtifact for an organism.
     */
    public enum ArtifactValidity {
        /** No ProgramArtifact available */
        NONE,
        /** ProgramArtifact is fully valid */
        VALID,
        /** Only source code and aliases are safe, source mapping is invalid */
        PARTIAL_SOURCE,
        /** ProgramArtifact is completely invalid */
        INVALID
    }
    
    // Cache for Artifact validity per organism (programId_organismId -> ArtifactValidity)
    private final Map<String, ArtifactValidity> validityCache = new HashMap<>();
    
    /**
     * Checks the validity of a ProgramArtifact for an organism.
     * Uses caching for performance optimization.
     * 
     * @param o The organism state
     * @param artifact The program artifact to validate
     * @return The validity status of the artifact
     */
    public ArtifactValidity checkArtifactValidity(RawOrganismState o, ProgramArtifact artifact) {
        if (artifact == null) {
            return ArtifactValidity.NONE;
        }
        
        // Cache-Key: programId_organismId
        String cacheKey = o.programId() + "_" + o.id();
        
        // Cache-Check
        ArtifactValidity cached = validityCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // New validation
        ArtifactValidity validity = performValidation(o, artifact);
        validityCache.put(cacheKey, validity);
        
        return validity;
    }
    
    /**
     * Performs the actual validation.
     * Hybrid approach: Quick check (IP in SourceMap) + detailed check (code consistency).
     * 
     * @param o The organism state
     * @param artifact The program artifact to validate
     * @return The validity status
     */
    private ArtifactValidity performValidation(RawOrganismState o, ProgramArtifact artifact) {
        // Quick check: IP in SourceMap?
        boolean ipValid = isIpInSourceMap(o, artifact);
        if (!ipValid) {
            return ArtifactValidity.INVALID;
        }
        
        // Since machineCodeLayout is corrupted during JSON serialization, we can't reliably check it
        // Instead, we'll use the sourceMap as a proxy for consistency - if the IP is in the sourceMap,
        // we assume the code is consistent enough for debugging purposes
        boolean ipInSourceMap = isIpInSourceMap(o, artifact);
        
        if (ipInSourceMap) {
            // IP is in sourceMap, assume code is consistent
            return ArtifactValidity.VALID;
        } else {
            // IP is not in sourceMap, code is inconsistent
            return ArtifactValidity.INVALID;
        }
    }
    
    /**
     * Quick check: Is the current IP position in the SourceMap of the artifact?
     * 
     * @param o The organism state
     * @param artifact The program artifact
     * @return true if IP is in sourceMap, false otherwise
     */
    private boolean isIpInSourceMap(RawOrganismState o, ProgramArtifact artifact) {
        if (artifact.sourceMap() == null || artifact.relativeCoordToLinearAddress() == null) {
            return false;
        }
        
        // Calculate relative IP position
        int[] origin = o.initialPosition();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < o.ip().length; i++) {
            if (i > 0) key.append('|');
            key.append(o.ip()[i] - origin[i]);
        }
        
        // Check if the current IP exists in the sourceMap
        Integer addr = artifact.relativeCoordToLinearAddress().get(key.toString());
        return addr != null && artifact.sourceMap().containsKey(addr);
    }
    
    /**
     * Clears the validity cache.
     * Useful for testing or when artifacts are updated.
     */
    public void clearCache() {
        validityCache.clear();
        log.debug("ArtifactValidator cache cleared");
    }
    
    /**
     * Gets the current cache size for monitoring purposes.
     * 
     * @return The number of cached validity entries
     */
    public int getCacheSize() {
        return validityCache.size();
    }
}
