package org.evochora.datapipeline;

/**
 * Helper class for creating test metadata with resolvedConfigJson.
 * <p>
 * Since SimulationMetadata now stores most configuration in resolvedConfigJson,
 * this helper provides convenient methods to create the JSON for tests.
 */
public final class TestMetadataHelper {

    private TestMetadataHelper() {
        // Utility class
    }

    /**
     * Creates a minimal resolvedConfigJson for tests.
     *
     * @param width Environment width
     * @param height Environment height
     * @return JSON string for resolvedConfigJson
     */
    public static String createResolvedConfigJson(int width, int height) {
        return createResolvedConfigJson(width, height, true, 1);
    }

    /**
     * Creates a resolvedConfigJson for tests with sampling interval.
     *
     * @param width Environment width
     * @param height Environment height
     * @param toroidal Whether environment is toroidal
     * @param samplingInterval Sampling interval
     * @return JSON string for resolvedConfigJson
     */
    public static String createResolvedConfigJson(int width, int height, boolean toroidal, int samplingInterval) {
        String topology = toroidal ? "TORUS" : "BOUNDED";
        return String.format("""
            {
                "environment": {
                    "shape": [%d, %d],
                    "topology": "%s"
                },
                "samplingInterval": %d,
                "accumulatedDeltaInterval": 100,
                "snapshotInterval": 10,
                "chunkInterval": 1,
                "tickPlugins": [],
                "organisms": [],
                "runtime": {
                    "organism": {
                        "max-energy": 32767,
                        "max-entropy": 8191,
                        "error-penalty-cost": 10
                    },
                    "thermodynamics": {
                        "default": {
                            "className": "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                            "options": {
                                "base-energy": 1,
                                "base-entropy": 1
                            }
                        },
                        "overrides": {
                            "instructions": {},
                            "families": {}
                        }
                    }
                }
            }
            """, width, height, topology, samplingInterval);
    }

    /**
     * Creates a Builder for more complex configurations.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int width = 100;
        private int height = 100;
        private boolean toroidal = true;
        private int samplingInterval = 1;
        private int accumulatedDeltaInterval = 100;
        private int snapshotInterval = 10;
        private int chunkInterval = 1;
        private String tickPluginsJson = "[]";
        private String organismsJson = "[]";

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public Builder shape(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder toroidal(boolean toroidal) {
            this.toroidal = toroidal;
            return this;
        }

        public Builder samplingInterval(int samplingInterval) {
            this.samplingInterval = samplingInterval;
            return this;
        }

        public Builder accumulatedDeltaInterval(int interval) {
            this.accumulatedDeltaInterval = interval;
            return this;
        }

        public Builder snapshotInterval(int interval) {
            this.snapshotInterval = interval;
            return this;
        }

        public Builder chunkInterval(int interval) {
            this.chunkInterval = interval;
            return this;
        }

        public Builder tickPluginsJson(String json) {
            this.tickPluginsJson = json;
            return this;
        }

        public Builder organismsJson(String json) {
            this.organismsJson = json;
            return this;
        }

        public String build() {
            String topology = toroidal ? "TORUS" : "BOUNDED";
            return String.format("""
                {
                    "environment": {
                        "shape": [%d, %d],
                        "topology": "%s"
                    },
                    "samplingInterval": %d,
                    "accumulatedDeltaInterval": %d,
                    "snapshotInterval": %d,
                    "chunkInterval": %d,
                    "tickPlugins": %s,
                    "organisms": %s,
                    "runtime": {
                        "organism": {
                            "max-energy": 32767,
                            "max-entropy": 8191,
                            "error-penalty-cost": 10
                        },
                        "thermodynamics": {
                            "default": {
                                "className": "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                                "options": {
                                    "base-energy": 1,
                                    "base-entropy": 1
                                }
                            },
                            "overrides": {
                                "instructions": {},
                                "families": {}
                            }
                        }
                    }
                }
                """, width, height, topology, samplingInterval, accumulatedDeltaInterval,
                snapshotInterval, chunkInterval, tickPluginsJson, organismsJson);
        }
    }
}
