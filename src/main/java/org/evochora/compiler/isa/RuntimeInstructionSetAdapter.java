// in: src/main/java/org/evochora/compiler/isa/RuntimeInstructionSetAdapter.java

package org.evochora.compiler.isa;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.isa.instructions.ConditionalInstruction;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Adapter that maps the runtime ISA static API to the stable compiler interface.
 */
public final class RuntimeInstructionSetAdapter implements IInstructionSet {

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Integer> getInstructionIdByName(String name) {
        return Optional.ofNullable(Instruction.getInstructionIdByName(name));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Signature> getSignatureById(int id) {
        Optional<InstructionSignature> sig = Instruction.getSignatureById(id);
        return sig.map(s -> (Signature) () -> s.argumentTypes().stream().map(a -> switch (a) {
            case REGISTER -> ArgKind.REGISTER;
            case LOCATION_REGISTER -> ArgKind.LOCATION_REGISTER;
            case LITERAL -> ArgKind.LITERAL;
            case VECTOR -> ArgKind.VECTOR;
            case LABEL -> ArgKind.LABEL;
        }).toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Integer> resolveRegisterToken(String token) {
        return Instruction.resolveRegToken(token);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<String> negatedConditional(String opcode) {
        return ConditionalInstruction.negationOf(opcode);
    }

    /**
     * {@inheritDoc}
     * <p>Derived from the runtime's bank declarations, so a bank added there is known here
     * without a change to the compiler.</p>
     */
    @Override
    public List<RegisterBankInfo> registerBanks() {
        List<RegisterBank> procScoped = RegisterBank.allProcScoped();
        return Arrays.stream(RegisterBank.values())
                .map(bank -> new RegisterBankInfo(bank.prefix, bank.base, bank.count, bank.isLocation,
                        bank.isForbidden, bank.isAlwaysAvailable, procScoped.contains(bank)))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requiresTypedLiterals() {
        return Config.STRICT_TYPING;
    }
}