package org.evochora.datapipeline.api.contracts;

import java.util.Arrays;

/**
 * Defines the static properties of the simulation world.
 */
public class EnvironmentProperties {

    private int[] worldShape;
    private WorldTopology topology;

    /**
     * Default constructor for deserialization.
     */
    public EnvironmentProperties() {
    }

    /**
     * Constructs a new EnvironmentProperties object.
     *
     * @param worldShape An array of integers defining the world's dimensions (e.g., {width, height}).
     * @param topology   The topology of the world's boundaries (e.g., TORUS or BOUNDED).
     */
    public EnvironmentProperties(int[] worldShape, WorldTopology topology) {
        this.worldShape = worldShape;
        this.topology = topology;
    }

    /**
     * Gets the shape of the world.
     *
     * @return An array of integers representing the dimensions of the world.
     */
    public int[] getWorldShape() {
        return worldShape;
    }

    /**
     * Sets the shape of the world.
     *
     * @param worldShape An array of integers representing the dimensions of the world.
     */
    public void setWorldShape(int[] worldShape) {
        this.worldShape = worldShape;
    }

    /**
     * Gets the topology of the world.
     *
     * @return The {@link WorldTopology} of the world.
     */
    public WorldTopology getTopology() {
        return topology;
    }

    /**
     * Sets the topology of the world.
     *
     * @param topology The {@link WorldTopology} of the world.
     */
    public void setTopology(WorldTopology topology) {
        this.topology = topology;
    }

    @Override
    public String toString() {
        return "EnvironmentProperties{" +
                "worldShape=" + Arrays.toString(worldShape) +
                ", topology=" + topology +
                '}';
    }
}
