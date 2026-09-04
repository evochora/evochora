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
	 * Looks up a molecule type by the name source uses for it, such as {@code DATA} or
	 * {@code STRUCTURE}.
	 *
	 * @param name The type name, in any letter case.
	 * @return The type's code in a cell, or empty if the target has no type of that name.
	 */
	Optional<Integer> moleculeType(String name);

	/**
	 * Packs a molecule into the value a cell holds.
	 *
	 * @param type  The molecule type, as returned by {@link #moleculeType}.
	 * @param value The molecule value.
	 * @return The packed cell.
	 */
	int encodeCell(int type, int value);

	/**
	 * The value that stands for a label name in the machine code: a LABEL molecule carries it,
	 * a LABELREF operand carries the value of the label it refers to, and the runtime matches
	 * the two by Hamming distance. Definitions and references use this one derivation.
	 *
	 * @param name The label name, qualified as it is in the layout.
	 * @return The value, reduced to the bits a label value may use; never negative.
	 */
	int labelValue(String name);

	/**
	 * Reads a register as source writes it: a bank's prefix followed by an index. The index is
	 * taken as written and may lie beyond the bank; {@link RegisterRef#inBounds()} tells.
	 *
	 * @param text The text of one token, in any letter case.
	 * @return The bank and the index, or empty if the text does not have that shape for any
	 *         bank of the target that has registers.
	 */
	default Optional<RegisterRef> parseRegister(String text) {
		String upper = text.toUpperCase();
		for (RegisterBankInfo bank : registerBanks()) {
			if (bank.count() > 0 && upper.startsWith(bank.prefix())) {
				String suffix = upper.substring(bank.prefix().length());
				if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) {
					return Optional.empty();
				}
				try {
					return Optional.of(new RegisterRef(bank, Integer.parseInt(suffix)));
				} catch (NumberFormatException tooLong) {
					return Optional.of(new RegisterRef(bank, Integer.MAX_VALUE));
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * A register as source names it: a bank and an index into it.
	 *
	 * @param bank  The bank the prefix named.
	 * @param index The index as written, which may lie beyond the bank.
	 */
	record RegisterRef(RegisterBankInfo bank, int index) {
		/**
		 * Tells whether the index names a register the bank has.
		 *
		 * @return Whether the index names a register the bank has.
		 */
		public boolean inBounds() {
			return index >= 0 && index < bank.count();
		}

		/**
		 * Computes the register's ID from the bank's base and the index.
		 *
		 * @return The register's ID, meaningful only when {@link #inBounds()}.
		 */
		public int id() {
			return bank.base() + index;
		}
	}

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
		 * Names the bank as source and messages do.
		 *
		 * @return The bank's name, the prefix without the percent sign.
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



