package org.evochora.compiler.internal.legacy;

/**
 * Repräsentiert eine einzelne Codezeile und ihren Ursprungskontext.
 * Dieses Objekt wird durch alle Phasen des Assemblers gereicht.
 */
public record AnnotatedLine(
        String content,
        int originalLineNumber,
        String originalFileName
) {}