package org.evochora.compiler.expression;

import org.evochora.compiler.model.expression.OperationExpression;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The operations a compilation knows, filled by the features and read by every phase that
 * computes. An operation is keyed by its name together with its notation; several operations
 * under one key are the overloads of one operation, told apart by the kinds of their operands,
 * and {@link #find} returns them as one.
 * <p>
 * The registry holds the rules that keep the language readable, and rejects a registration that
 * breaks one when it happens:
 * <ul>
 *   <li>one key stands at one level, and no two of its overloads take the same parameter kinds;</li>
 *   <li>the notation fits the level: a function stands at {@link Level#INDEX}, a prefix operator
 *       at {@link Level#PREFIX}, a chain at {@link Level#CHAIN}, an infix operator at one of the
 *       other levels, and {@link Level#INDEX} holds no infix operator but the index access
 *       {@code []};</li>
 *   <li>a name is a function or an operator, never both, and an operator is an infix or a chain,
 *       never both; an operator may be a prefix and an infix at once, as {@code -} is.</li>
 * </ul>
 * Once every feature has registered, {@link #validate} holds the names against the words of the
 * preprocessor.
 */
public final class OperationRegistry {

    private final Map<Key, Overloads> operations = new LinkedHashMap<>();

    /**
     * An operation's key: its name, upper-cased, and its notation.
     */
    private record Key(String name, Notation notation) {
    }

    /**
     * Registers an operation, as an overload of the operations already registered under its name
     * and notation.
     *
     * @param operation The operation.
     * @throws IllegalStateException if the operation breaks one of the rules of the registry; the
     *         message names the operation.
     */
    public void register(IOperation operation) {
        String name = normalize(operation.name());
        Notation notation = operation.notation();
        if (operation.signatures().isEmpty()) {
            throw new IllegalStateException("Operation '" + name + "' has no signature.");
        }
        checkLevelFitsNotation(name, notation, operation.level());
        for (Notation other : notationsOf(name)) {
            if ((other == Notation.FUNCTION) != (notation == Notation.FUNCTION)) {
                throw new IllegalStateException("Operation '" + name + "' cannot be both a function and an operator.");
            }
            if (EnumSet.of(other, notation).equals(EnumSet.of(Notation.INFIX, Notation.CHAIN))) {
                throw new IllegalStateException("Operation '" + name + "' cannot be both an infix and a chain operator.");
            }
        }
        Key key = new Key(name, notation);
        Overloads existing = operations.get(key);
        if (existing != null && existing.level() != operation.level()) {
            throw new IllegalStateException("Operation '" + name + "' as " + notation + " stands at level "
                    + existing.level() + " and cannot also stand at level " + operation.level() + ".");
        }
        List<Signature> known = new ArrayList<>(existing != null ? existing.signatures() : List.of());
        for (Signature signature : operation.signatures()) {
            for (Signature other : known) {
                if (other.parameters().equals(signature.parameters())) {
                    throw new IllegalStateException("Operation '" + name + "' as " + notation
                            + " takes " + describe(signature) + " twice.");
                }
            }
            known.add(signature);
        }
        operations.computeIfAbsent(key, k -> new Overloads(name, notation, operation.level())).add(operation);
    }

    /**
     * Finds the operation registered under a name and a notation, with all its overloads.
     *
     * @param name     The name, in any letter case.
     * @param notation The notation.
     * @return The operation, or empty if none is registered under the key.
     */
    public Optional<IOperation> find(String name, Notation notation) {
        return Optional.ofNullable(operations.get(new Key(normalize(name), notation)));
    }

    /**
     * Returns the level of the operation registered under a name and a notation.
     *
     * @param name     The name, in any letter case.
     * @param notation The notation.
     * @return The level, or empty if no operation is registered under the key.
     */
    public Optional<Level> levelOf(String name, Notation notation) {
        return find(name, notation).map(IOperation::level);
    }

    /**
     * Returns the names nothing in a program may define, each with what it is, for the message
     * that rejects a definition: the name of every operation, upper-cased, as {@code "a function"}
     * or {@code "an operator"}, and {@code @}, the cursor, with the empty text, as it is neither.
     *
     * @return An unmodifiable map from the upper-cased name to what it is, a copy taken now.
     */
    public Map<String, String> reservedNames() {
        Map<String, String> names = new LinkedHashMap<>();
        operations.keySet().forEach(key ->
                names.put(key.name(), key.notation() == Notation.FUNCTION ? "a function" : "an operator"));
        names.put(INameResolution.CURSOR, "");
        return Collections.unmodifiableMap(names);
    }

    /**
     * Holds the operations against the words of the preprocessor, once every feature has
     * registered: an operation may not be named like a directive the preprocessor acts on wherever
     * it stands. The typing operator {@code :} is the exception, as the preprocessor reads a colon
     * at the start of a statement only.
     *
     * @param preprocessorWords The words the preprocessor acts on, in any letter case.
     * @throws IllegalStateException if an operation is named like one of them.
     */
    public void validate(Set<String> preprocessorWords) {
        Set<String> words = new LinkedHashSet<>();
        preprocessorWords.forEach(word -> words.add(normalize(word)));
        for (Key key : operations.keySet()) {
            if (!OperationExpression.TYPING.equals(key.name()) && words.contains(key.name())) {
                throw new IllegalStateException("Operation '" + key.name() + "' is a preprocessor directive.");
            }
        }
    }

    private List<Notation> notationsOf(String name) {
        List<Notation> notations = new ArrayList<>();
        for (Key key : operations.keySet()) {
            if (key.name().equals(name)) {
                notations.add(key.notation());
            }
        }
        return notations;
    }

    private static void checkLevelFitsNotation(String name, Notation notation, Level level) {
        boolean fits = switch (notation) {
            case FUNCTION -> level == Level.INDEX;
            case PREFIX -> level == Level.PREFIX;
            case CHAIN -> level == Level.CHAIN;
            case INFIX -> OperationExpression.INDEX.equals(name)
                    ? level == Level.INDEX
                    : level != Level.INDEX && level != Level.PREFIX && level != Level.CHAIN;
        };
        if (!fits) {
            throw new IllegalStateException("Operation '" + name + "' as " + notation + " cannot stand at level " + level + ".");
        }
    }

    private static String describe(Signature signature) {
        List<String> kinds = new ArrayList<>();
        signature.parameters().forEach(kind -> kinds.add(kind.name().toLowerCase(Locale.ROOT)));
        return "(" + String.join(", ", kinds) + (signature.lastRepeated() ? "…" : "") + ")";
    }

    private static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    /**
     * The operations registered under one key, seen as one operation: its signatures are theirs
     * together, and it computes with the operation whose signature fits the operands.
     */
    private static final class Overloads implements IOperation {
        private final String name;
        private final Notation notation;
        private final Level level;
        private final List<IOperation> members = new ArrayList<>();

        Overloads(String name, Notation notation, Level level) {
            this.name = name;
            this.notation = notation;
            this.level = level;
        }

        void add(IOperation operation) {
            members.add(operation);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Notation notation() {
            return notation;
        }

        @Override
        public Level level() {
            return level;
        }

        @Override
        public List<Signature> signatures() {
            List<Signature> all = new ArrayList<>();
            members.forEach(member -> all.addAll(member.signatures()));
            return Collections.unmodifiableList(all);
        }

        @Override
        public OperationResult compute(List<Value> operands, IEvaluationContext context) {
            List<Kind> kinds = operands.stream().map(Value::kind).toList();
            for (IOperation member : members) {
                if (member.signatures().stream().anyMatch(signature -> signature.accepts(kinds))) {
                    return member.compute(operands, context);
                }
            }
            return new OperationResult.Error(Evaluator.invalidOperands(name, kinds));
        }
    }
}
