package org.evochora.datapipeline.services.debugindexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.datapipeline.services.debugindexer.ArtifactValidator.ArtifactValidity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds source views for organisms by analyzing their source code and generating annotations.
 * Handles source mapping, line generation, and inline span creation for debugging purposes.
 */
public class SourceViewBuilder {
    
    private static final Logger log = LoggerFactory.getLogger(SourceViewBuilder.class);
    
    private final SourceAnnotator sourceAnnotator;
    
    // Performance optimization: cache for coordinate keys to avoid repeated string concatenation
    private final Map<String, String> coordinateKeyCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    public SourceViewBuilder() {
        this.sourceAnnotator = new SourceAnnotator();
    }
    
    /**
     * Builds a complete source view for an organism, including source lines and annotations.
     * 
     * @param organism The organism to build the source view for
     * @param artifact The program artifact containing source information
     * @param validity The validity status of the artifact
     * @return A complete source view, or null if the artifact is invalid
     */
    public PreparedTickState.SourceView buildSourceView(RawOrganismState organism, ProgramArtifact artifact, ArtifactValidity validity) {
        // Return null when no valid artifact - Jackson will omit the field entirely
        if (artifact == null || validity == ArtifactValidity.INVALID) {
            return null;
        }
        
        // Calculate fileName and currentLine from organism's IP position and artifact's source mapping
        String fileName = calculateFileName(organism, artifact);
        Integer currentLine = calculateCurrentLine(organism, artifact);
        
        // Generate source lines and annotations when artifact is available
        List<PreparedTickState.SourceLine> lines = buildSourceLines(artifact, fileName, currentLine);
        List<PreparedTickState.InlineSpan> inlineSpans = buildInlineSpans(organism, artifact, fileName, currentLine);
        
        // Return a proper SourceView with calculated values
        return new PreparedTickState.SourceView(
            fileName,           // Calculated fileName or fallback
            currentLine,        // Calculated currentLine or fallback
            lines,              // Populated source lines
            inlineSpans         // Generated annotations
        );
    }
    
    /**
     * Calculates the file name for the organism based on its current IP position.
     * 
     * @param organism The organism
     * @param artifact The program artifact
     * @return The file name, or "unknown.s" as fallback
     */
    private String calculateFileName(RawOrganismState organism, ProgramArtifact artifact) {
        try {
            // Get organism's current IP coordinates
            int[] ipCoords = organism.ip();
            if (ipCoords != null && ipCoords.length >= 2) {
                int[] ipArray = new int[]{ipCoords[0], ipCoords[1]};
                
                // Get organism's starting position
                int[] startingPos = organism.initialPosition();
                if (startingPos != null && startingPos.length >= 2) {
                    // Calculate relative coordinates by subtracting starting position
                    int[] relativeCoords = new int[]{ipArray[0] - startingPos[0], ipArray[1] - startingPos[1]};
                    
                    // Use direct coordinate lookup instead of string concatenation for better performance
                    Integer linearAddress = findLinearAddressForCoordinates(artifact, relativeCoords);
                    
                    if (linearAddress != null) {
                        // Look up source information for this address
                        SourceInfo sourceInfo = artifact.sourceMap().get(linearAddress);
                        if (sourceInfo != null) {
                            return sourceInfo.fileName();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log error but continue - this is debug info, shouldn't break the system
            log.debug("Error calculating file name for organism {}: {}", organism.id(), e.getMessage());
        }
        
        // Fallback to default value if calculation failed
        return "unknown.s";
    }
    
    /**
     * Calculates the current line number for the organism based on its IP position.
     * 
     * @param organism The organism
     * @param artifact The program artifact
     * @return The current line number, or 1 as fallback
     */
    private Integer calculateCurrentLine(RawOrganismState organism, ProgramArtifact artifact) {
        try {
            // Get organism's current IP coordinates
            int[] ipCoords = organism.ip();
            if (ipCoords != null && ipCoords.length >= 2) {
                int[] ipArray = new int[]{ipCoords[0], ipCoords[1]};
                
                // Get organism's starting position
                int[] startingPos = organism.initialPosition();
                if (startingPos != null && startingPos.length >= 2) {
                    // Calculate relative coordinates by subtracting starting position
                    int[] relativeCoords = new int[]{ipArray[0] - startingPos[0], ipArray[1] - startingPos[1]};
                    
                    // Use direct coordinate lookup instead of string concatenation for better performance
                    Integer linearAddress = findLinearAddressForCoordinates(artifact, relativeCoords);
                    
                    if (linearAddress != null) {
                        // Look up source information for this address
                        SourceInfo sourceInfo = artifact.sourceMap().get(linearAddress);
                        if (sourceInfo != null) {
                            return sourceInfo.lineNumber();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log error but continue - this is debug info, shouldn't break the system
            log.debug("Error calculating current line for organism {}: {}", organism.id(), e.getMessage());
        }
        
        // Fallback to default value if calculation failed
        return 1;
    }
    
    /**
     * Optimized coordinate lookup that avoids string concatenation for better performance.
     * Uses the linearAddressToCoord map to find coordinates directly.
     * 
     * @param artifact The program artifact
     * @param relativeCoords The relative coordinates to look up
     * @return The linear address, or null if not found
     */
    private Integer findLinearAddressForCoordinates(ProgramArtifact artifact, int[] relativeCoords) {
        // Use cached coordinate key to avoid repeated string concatenation
        String coordKey = getCachedCoordinateKey(relativeCoords[0], relativeCoords[1]);
        return artifact.relativeCoordToLinearAddress().get(coordKey);
    }
    
    /**
     * Gets a cached coordinate key to avoid repeated string concatenation.
     * This is a significant performance optimization for the debug indexer.
     * 
     * @param x The x coordinate
     * @param y The y coordinate
     * @return The coordinate key string
     */
    private String getCachedCoordinateKey(int x, int y) {
        // Create a cache key that's fast to compute
        String cacheKey = x + "," + y;
        
        // Use computeIfAbsent for thread-safe caching
        return coordinateKeyCache.computeIfAbsent(cacheKey, k -> x + "|" + y);
    }
    
    /**
     * Builds source lines for the specified file.
     * 
     * @param artifact The program artifact
     * @param fileName The file name to build lines for
     * @param currentLine The current active line number
     * @return A list of source lines
     */
    private List<PreparedTickState.SourceLine> buildSourceLines(ProgramArtifact artifact, String fileName, Integer currentLine) {
        List<PreparedTickState.SourceLine> lines = new ArrayList<>();
        
        // Get source lines for the current file
        List<String> sourceLines = artifact.sources().get(fileName);
        if (sourceLines != null) {
            for (int i = 0; i < sourceLines.size(); i++) {
                String lineContent = sourceLines.get(i);
                int lineNumber = i + 1;
                boolean isCurrent = lineNumber == currentLine;
                
                // Create source line
                lines.add(new PreparedTickState.SourceLine(
                    lineNumber, lineContent, isCurrent, 
                    new ArrayList<>(), new ArrayList<>()  // prolog/epilog empty for now
                ));
            }
        }
        
        return lines;
    }
    
    /**
     * Builds inline spans (annotations) for the source code.
     * 
     * @param organism The organism
     * @param artifact The program artifact
     * @param fileName The file name
     * @param currentLine The current line number
     * @return A list of inline spans
     */
    private List<PreparedTickState.InlineSpan> buildInlineSpans(RawOrganismState organism, ProgramArtifact artifact, String fileName, Integer currentLine) {
        List<PreparedTickState.InlineSpan> inlineSpans = new ArrayList<>();
        
        // Get source lines for the current file
        List<String> sourceLines = artifact.sources().get(fileName);
        if (sourceLines != null) {
            for (int i = 0; i < sourceLines.size(); i++) {
                String lineContent = sourceLines.get(i);
                int lineNumber = i + 1;
                
                // Generate annotations for this line (only for the active line)
                boolean isActiveLine = lineNumber == currentLine;
                if (isActiveLine) {
                    List<PreparedTickState.InlineSpan> lineSpans = sourceAnnotator.annotate(
                        organism, artifact, fileName, lineContent, lineNumber, isActiveLine);
                    inlineSpans.addAll(lineSpans);
                }
            }
        }
        
        return inlineSpans;
    }
}
