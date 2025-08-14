package org.evochora.compiler.isa;

import java.util.List;
import java.util.Optional;

/**
 * Stable ISA interface used by the compiler backend to avoid direct coupling
 * to runtime ISA implementation details.
 */
public interface IInstructionSet {

	Optional<Integer> getInstructionIdByName(String name);
	Optional<Signature> getSignatureById(int id);
	int getInstructionLengthById(int id);
	Optional<Integer> resolveRegisterToken(String token);

	enum ArgKind { REGISTER, LITERAL, VECTOR, LABEL }

	interface Signature {
		List<ArgKind> argumentTypes();
		default int getArity() { return argumentTypes().size(); }
	}
}



