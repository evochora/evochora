package org.evochora.compiler.isa;

import java.util.List;
import java.util.Optional;

/**
 * Stable ISA interface used by the compiler backend to avoid direct coupling
 * to runtime ISA implementation details.
 */
public interface IInstructionSet {

	/**
	 * Gets the ID of an instruction by its name.
	 * @param name The name of the instruction.
	 * @return An optional containing the instruction ID, or empty if not found.
	 */
	Optional<Integer> getInstructionIdByName(String name);

	/**
	 * Gets the signature of an instruction by its ID.
	 * @param id The ID of the instruction.
	 * @return An optional containing the instruction signature, or empty if not found.
	 */
	Optional<Signature> getSignatureById(int id);

	/**
	 * Resolves a register token (e.g., "%DR0") to its ID.
	 * @param token The register token to resolve.
	 * @return An optional containing the register ID, or empty if not found.
	 */
	Optional<Integer> resolveRegisterToken(String token);

	/**
	 * Names the conditional that skips the next instruction exactly when the given one does
	 * not. The two take the same operands, so the negation can stand in for the original
	 * wherever a condition has to be inverted.
	 *
	 * @param opcode The name of an instruction, in any letter case.
	 * @return The name of the negated conditional, or empty if the instruction is no conditional.
	 */
	Optional<String> negatedConditional(String opcode);

	/**
	 * Describes the register banks of the target, in the order the target declares them.
	 * A register is written as the bank's prefix followed by an index below the bank's count.
	 *
	 * @return The banks, including those with no registers allocated, which have a count of zero.
	 */
	List<RegisterBankInfo> registerBanks();

	/**
	 * Tells whether the target rejects an untyped literal where a literal operand is expected.
	 *
	 * @return {@code true} if every literal operand has to carry a molecule type.
	 */
	boolean requiresTypedLiterals();

	/**
	 * What the compiler knows about one register bank of the target.
	 *
	 * @param prefix          How a register of the bank starts in source, including the
	 *                        leading percent sign, e.g. {@code %PDR}.
	 * @param base            The ID of the bank's first register; index {@code i} has ID
	 *                        {@code base + i}.
	 * @param count           How many registers the bank has.
	 * @param location        Whether the registers hold coordinate vectors rather than scalars.
	 * @param forbidden       Whether source must not name the registers; the compiler assigns
	 *                        them itself.
	 * @param alwaysAvailable Whether source may name the registers outside a procedure.
	 * @param procScoped      Whether source may name the registers only inside a procedure.
	 */
	record RegisterBankInfo(String prefix, int base, int count, boolean location, boolean forbidden,
							boolean alwaysAvailable, boolean procScoped) {
		/**
		 * @return The bank's name as source and messages use it, the prefix without the percent sign.
		 */
		public String name() {
			return prefix.substring(1);
		}
	}

	/**
	 * The kind of an instruction argument.
	 */
	enum ArgKind {
		/** A data register operand (DR, PDR, FDR, SDR). */
		REGISTER,
		/** A location register operand (LR, PLR, FLR, SLR). */
		LOCATION_REGISTER,
		/** A literal value operand. */
		LITERAL,
		/** A vector operand. */
		VECTOR,
		/** A label operand. */
		LABEL
	}

	/**
	 * Represents the signature of an instruction.
	 */
	interface Signature {
		/**
		 * Describes the operands the instruction expects, in the order they follow the opcode.
		 *
		 * @return The list of argument types for the instruction.
		 */
		List<ArgKind> argumentTypes();

		/**
		 * Reports how many operands the instruction expects, counted from {@link #argumentTypes()}.
		 *
		 * @return The number of arguments for the instruction.
		 */
		default int getArity() { return argumentTypes().size(); }
	}
}



