package org.evochora.compiler.internal;

/**
 * One cell of a program's code in the JSON form of an artifact: its position relative to the
 * program's origin and the encoded molecule it holds, named as the {@code InstructionMapping} of
 * {@code metadata_contracts.proto} names them.
 *
 * @param position    The coordinate relative to the program's origin.
 * @param instruction The encoded molecule of the cell.
 */
public record InstructionMapping(int[] position, int instruction) {
}
