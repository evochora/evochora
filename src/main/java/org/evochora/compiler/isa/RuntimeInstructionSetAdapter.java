// in: src/main/java/org/evochora/compiler/isa/RuntimeInstructionSetAdapter.java

package org.evochora.compiler.isa;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionSignature;

import java.util.List;
import java.util.Optional;

/**
 * Adapter that maps the runtime ISA static API to the stable compiler interface.
 */
public final class RuntimeInstructionSetAdapter implements IInstructionSet {

    @Override
    public Optional<Integer> getInstructionIdByName(String name) {
        return Optional.ofNullable(Instruction.getInstructionIdByName(name));
    }

    @Override
    public Optional<Signature> getSignatureById(int id) {
        Optional<InstructionSignature> sig = Instruction.getSignatureById(id);
        return sig.map(s -> (Signature) () -> s.argumentTypes().stream().map(a -> switch (a) {
            case REGISTER -> ArgKind.REGISTER;
            case LITERAL -> ArgKind.LITERAL;
            case VECTOR -> ArgKind.VECTOR;
            case LABEL -> ArgKind.LABEL;
        }).toList());
    }

    @Override
    public int getInstructionLengthById(int id) {
        return Instruction.getInstructionLengthById(id);
    }

    @Override
    public Optional<Integer> resolveRegisterToken(String token) {
        // Ruft jetzt die zentrale Methode auf
        return Instruction.resolveRegToken(token);
    }
}