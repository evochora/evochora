package org.evochora.runtime.spi;

import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.OrganismRandom;

/**
 * The label addressing model of a run: how a jump finds its target label, and how label values
 * pass from a parent to its child.
 * <p>
 * The two belong together. Whether a reference may resolve into another organism's code depends on
 * which label values organisms share, and that is decided by what happens to them at birth. A
 * strategy therefore answers both questions, and both are configured in one block: the runtime
 * configuration names the implementing class under {@code label-matching.className} and hands its
 * {@code options} to a constructor taking a {@code com.typesafe.config.Config}.
 * <p>
 * A strategy sees only labels that are jump targets. A label written with a non-zero marker belongs
 * to a body that is still under construction; the label index keeps it out and reports it through
 * {@link #addLabel} once a fork or its owner's death has reset the marker.
 * <p>
 * Thread Safety: {@link #findTarget} and {@link #valuesMatch} are called concurrently from every
 * thread of the parallel wave and must not mutate the strategy. {@link #addLabel},
 * {@link #removeLabel}, {@link #changeOwner} and {@link #birthMask} are called only from the
 * simulation thread outside the wave, {@link #initialize} before any of them.
 */
public interface ILabelMatchingStrategy {

    /**
     * Tells the strategy the shape and topology of the world its labels lie in. The environment
     * calls this once, before it reports the first label, so that a strategy can order its labels
     * by where they are. A strategy that does not need the shape ignores the call.
     *
     * @param properties The properties of the world
     */
    default void initialize(EnvironmentProperties properties) {
        // nothing to prepare
    }

    /**
     * Estimates the memory the strategy needs to hold a number of labels, as an upper bound: the
     * run's memory estimate assumes how many labels a world can hold and asks the strategy what
     * that costs. The answer depends on how the strategy keeps its labels and on nothing else.
     *
     * @param labels The number of labels to hold
     * @return The bytes the strategy needs for them at most
     */
    long estimateMemoryBytes(long labels);

    /**
     * Resolves a label reference to the label it jumps to.
     * <p>
     * The result must depend only on the labels present, never on the order in which they were
     * added: an index rebuilt from a snapshot adds the same labels in a different order and must
     * resolve every lookup identically. It must not depend on the grid's memory layout either;
     * positions are compared by flat index. The world's shape and topology are what
     * {@link #initialize} told.
     *
     * @param searchValue The label value the reference carries, within the value field
     * @param codeOwner The ID of the organism executing the lookup; its own labels are its own
     * @param callerCoords The coordinates the distance to a label is measured from
     * @param random The random source of the organism executing the lookup; the only source of
     *               randomness a strategy may use here, so that a stochastic choice depends on the
     *               calling organism alone and never on the thread performing the lookup.
     *               Must not be null.
     * @return The flat index of the target label, or -1 if the reference resolves to none
     */
    int findTarget(int searchValue, int codeOwner, int[] callerCoords, OrganismRandom random);

    /**
     * Reports a label that became a jump target.
     *
     * @param labelValue The label's value, within the value field
     * @param flatIndex The flat index of the cell holding the label
     * @param owner The ID of the cell's owner; 0 for an unowned cell
     */
    void addLabel(int labelValue, int flatIndex, int owner);

    /**
     * Reports that a label reported through {@link #addLabel} is gone.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell that held the label
     * @param owner The ID the label was last reported under
     */
    void removeLabel(int labelValue, int flatIndex, int owner);

    /**
     * Reports that the cell of a label passed to another owner while the label stayed as it is.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell holding the label
     * @param oldOwner The ID the label was last reported under
     * @param newOwner The ID of the cell's new owner; 0 for an unowned cell
     */
    void changeOwner(int labelValue, int flatIndex, int oldOwner, int newOwner);

    /**
     * Tells whether a reference value can address a label value at all, whoever owns the label and
     * wherever it stands. Code that writes references — a mutation operator choosing a jump
     * target — asks this instead of knowing how a strategy compares values.
     *
     * @param searchValue The value a reference carries
     * @param labelValue The value of a label
     * @return {@code true} if a reference with {@code searchValue} can resolve to such a label
     */
    boolean valuesMatch(int searchValue, int labelValue);

    /**
     * Chooses the mask a newborn's label values are XORed with.
     * <p>
     * After the birth handlers of a newborn have run, the simulation applies the mask to every
     * label and label reference the newborn owns and records it on the newborn. The same mask on
     * both sides leaves every Hamming distance inside the organism as it was; what changes is how
     * the newborn's labels relate to everybody else's. A strategy under which label values simply
     * pass from parent to child keeps this default.
     *
     * @param randomProvider The root random provider, which serves the sequential parts of a tick
     * @return The mask, within the value field; 0 to leave the newborn's label values untouched
     */
    default int birthMask(IRandomProvider randomProvider) {
        return 0;
    }
}
