package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
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
     * Uses precise column information from TokenAnnotation for accurate positioning instead of string-splitting approach.
     * 
     * @param tokenAnnotations The token annotations from TokenAnnotator
     * @param lineNumber The line number for the annotations
     * @param sourceLine The source line content (used for fallback occurrence calculation)
     * @return List of InlineSpan objects ready for the web debugger
     */
    private List<InlineSpan> convertToInlineSpans(List<TokenAnnotation> tokenAnnotations, int lineNumber, String sourceLine) {
        List<InlineSpan> spans = new ArrayList<>();
        
        if (tokenAnnotations == null || tokenAnnotations.isEmpty()) {
            return spans;
        }

        // Group annotations by token text to calculate occurrences
        Map<String, List<TokenAnnotation>> tokenGroups = new HashMap<>();
        for (TokenAnnotation annotation : tokenAnnotations) {
            tokenGroups.computeIfAbsent(annotation.token(), k -> new ArrayList<>()).add(annotation);
        }
        
        // Convert each TokenAnnotation to InlineSpan using column-based positioning
        for (Map.Entry<String, List<TokenAnnotation>> entry : tokenGroups.entrySet()) {
            String tokenText = entry.getKey();
            List<TokenAnnotation> annotationsForToken = entry.getValue();
            
            // Sort annotations by column position to determine occurrence order
            annotationsForToken.sort((a, b) -> Integer.compare(a.column(), b.column()));
            
            // Create InlineSpan for each occurrence of this token
            for (int i = 0; i < annotationsForToken.size(); i++) {
                TokenAnnotation annotation = annotationsForToken.get(i);
                int occurrence = i + 1; // 1-based occurrence
                
                InlineSpan span = new InlineSpan(
                    lineNumber,
                    tokenText,
                    occurrence,
                    annotation.annotationText(),
                    annotation.kind()
                );
                
                spans.add(span);
            }
        }
        
        return spans;
    }
}
