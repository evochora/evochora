package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.debug.PreparedTickState.InlineSpan;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.indexer.annotation.TokenAnnotator;
import org.evochora.server.indexer.annotation.TokenAnnotation;

import java.util.*;

/**
 * Encapsulates the logic for creating source code annotations (InlineSpans).
 * Now uses TokenAnnotator for deterministic token analysis.
 */
public class SourceAnnotator {
    
    private final TokenAnnotator tokenAnnotator;
    
    public SourceAnnotator() {
        this.tokenAnnotator = new TokenAnnotator();
    }

    /**
     * Creates all annotations for a given source line using the deterministic TokenAnnotator.
     * @param o The raw state of the organism.
     * @param artifact The program artifact containing metadata.
     * @param sourceLine The content of the source code line.
     * @param lineNumber The line number.
     * @return A list of InlineSpan objects.
     */
    public List<InlineSpan> annotate(RawOrganismState o, ProgramArtifact artifact, String fileName, String sourceLine, int lineNumber, boolean isActiveLine) {
        if (artifact == null || sourceLine == null || fileName == null) {
            return Collections.emptyList();
        }

        // Only annotate the active line - runtime information is only valid there
        if (!isActiveLine) {
            return Collections.emptyList();
        }

        // Use TokenAnnotator for deterministic token analysis with precise file-based lookup
        List<TokenAnnotation> tokenAnnotations = tokenAnnotator.analyzeLine(fileName, lineNumber, artifact, o);
        
        // Convert TokenAnnotation to InlineSpan for web debugger compatibility
        return convertToInlineSpans(tokenAnnotations, lineNumber, sourceLine);
    }
    
    /**
     * Converts TokenAnnotation objects to InlineSpan objects for web debugger compatibility.
     * This method handles the conversion from our internal token analysis to the web debugger's expected format.
     * 
     * TODO: Future improvement - use precise column information from TokenInfo for accurate positioning
     * instead of string-splitting approach. This would require modifying TokenAnnotation to include column data.
     * 
     * @param tokenAnnotations The token annotations from TokenAnnotator
     * @param lineNumber The line number for the annotations
     * @param sourceLine The source line content for occurrence calculation
     * @return List of InlineSpan objects ready for the web debugger
     */
    private List<InlineSpan> convertToInlineSpans(List<TokenAnnotation> tokenAnnotations, int lineNumber, String sourceLine) {
        List<InlineSpan> spans = new ArrayList<>();
        
        if (tokenAnnotations == null || tokenAnnotations.isEmpty()) {
            return spans;
        }

        // Calculate token occurrences in the source line for proper positioning
        // This is a best-effort approach using string matching
        // TODO: Replace with precise column-based positioning using TokenInfo data
        Map<String, Integer> tokenOccurrences = new HashMap<>();
        String[] tokens = sourceLine.trim().split("\\s+");
        
        for (String token : tokens) {
            if (token != null && !token.trim().isEmpty()) {
                tokenOccurrences.compute(token, (k, v) -> (v == null) ? 1 : v + 1);
            }
        }
        
        // Convert each TokenAnnotation to InlineSpan
        for (TokenAnnotation tokenAnnotation : tokenAnnotations) {
            String tokenText = tokenAnnotation.token();
            
            // Find the occurrence of this token in the source line
            int occurrence = tokenOccurrences.getOrDefault(tokenText, 1);
            
            // Create InlineSpan with the converted data
            InlineSpan span = new InlineSpan(
                lineNumber,
                tokenText,
                occurrence,
                tokenAnnotation.annotationText(),
                tokenAnnotation.kind()
            );
            
            spans.add(span);
        }
        
        return spans;
    }
}
