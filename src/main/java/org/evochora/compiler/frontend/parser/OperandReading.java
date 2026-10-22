package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.model.ast.OperandNode;

/**
 * What reading a list of tokens apart from the token stream as one operand gave: the operand, or
 * why the tokens are none, in the words the parser would report them with.
 */
public sealed interface OperandReading permits OperandReading.Read, OperandReading.Failed {

    /**
     * The tokens are exactly one operand.
     *
     * @param operand The operand.
     */
    record Read(OperandNode operand) implements OperandReading {
    }

    /**
     * The tokens are no operand.
     *
     * @param reason One sentence, the parser's message for the first problem found, such as
     *               {@code Expected ')'.} or {@code Unexpected 'B'.}
     */
    record Failed(String reason) implements OperandReading {
    }
}
