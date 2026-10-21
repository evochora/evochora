package org.evochora.compiler.api;

/**
 * A region of lines left out, with the placement and the file of its directive line, before it
 * is put into the entry or the instance of injected tokens it belongs to:
 * {@link SourceView#withRecords} puts it there.
 *
 * @param placement The alias chain of the placement of the directive line.
 * @param fileName  The file of the directive line, the resolved path of its entry.
 * @param region    The region, with the instance it was decided in.
 */
public record PositionedLeftOut(String placement, String fileName, SourceFile.LeftOut region) {
}
