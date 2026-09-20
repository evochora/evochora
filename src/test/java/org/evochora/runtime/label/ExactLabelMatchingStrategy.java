package org.evochora.runtime.label;

import org.evochora.runtime.model.OrganismRandom;
import org.evochora.runtime.spi.ILabelMatchingStrategy;
import org.evochora.runtime.spi.IRandomProvider;

import java.util.Map;
import java.util.TreeMap;

/**
 * The smallest label matching strategy there can be, written against the interface alone: a
 * reference resolves to the label carrying exactly its value, whoever owns it, and every newborn
 * gets the mask its configuration names. It stands for a strategy a third party plugs in.
 */
public class ExactLabelMatchingStrategy implements ILabelMatchingStrategy {

    private final int birthMask;

    /** Label value → flat index of the label with the lowest flat index carrying it. */
    private final Map<Integer, TreeMap<Integer, Integer>> labels = new TreeMap<>();

    /**
     * @param options The strategy's options: {@code birthMask}, the mask every newborn gets
     */
    public ExactLabelMatchingStrategy(com.typesafe.config.Config options) {
        this.birthMask = options.hasPath("birthMask") ? options.getInt("birthMask") : 0;
    }

    @Override
    public long estimateMemoryBytes(long labels) {
        // A boxed key and value in a tree map entry per label, roughly
        return labels * 96;
    }

    @Override
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, OrganismRandom random) {
        TreeMap<Integer, Integer> carrying = labels.get(searchValue);
        return carrying == null || carrying.isEmpty() ? -1 : carrying.firstKey();
    }

    @Override
    public void addLabel(int labelValue, int flatIndex, int owner) {
        labels.computeIfAbsent(labelValue, k -> new TreeMap<>()).put(flatIndex, owner);
    }

    @Override
    public void removeLabel(int labelValue, int flatIndex, int owner) {
        TreeMap<Integer, Integer> carrying = labels.get(labelValue);
        if (carrying != null) {
            carrying.remove(flatIndex);
        }
    }

    @Override
    public void changeOwner(int labelValue, int flatIndex, int oldOwner, int newOwner) {
        labels.computeIfAbsent(labelValue, k -> new TreeMap<>()).put(flatIndex, newOwner);
    }

    @Override
    public boolean valuesMatch(int searchValue, int labelValue) {
        return searchValue == labelValue;
    }

    @Override
    public int birthMask(IRandomProvider randomProvider) {
        return birthMask;
    }
}
