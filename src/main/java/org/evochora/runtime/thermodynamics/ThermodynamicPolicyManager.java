package org.evochora.runtime.thermodynamics;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.spi.thermodynamics.IThermodynamicPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the loading and retrieval of thermodynamic policies.
 * This class parses the runtime configuration and assigns policies to instructions
 * based on the configured rules (instruction overrides, family overrides, default).
 * <p>
 * Optimized for performance: Uses array-based lookup by opcode ID for O(1) access.
 */
public class ThermodynamicPolicyManager {

    private static final Logger LOG = LoggerFactory.getLogger(ThermodynamicPolicyManager.class);

    // Initial array size (small to save memory)
    private static final int INITIAL_ARRAY_SIZE = 256;
    
    private IThermodynamicPolicy defaultPolicy;
    private final Map<String, IThermodynamicPolicy> instructionPolicies = new HashMap<>();
    private final Map<Class<? extends Instruction>, IThermodynamicPolicy> familyPolicies = new HashMap<>();
    
    // Non-volatile to avoid per-call overhead on ARM (~1-5ns × hundreds of millions of ticks).
    // Concurrent first-tick population may cause redundant resolves, which is benign (idempotent).
    private IThermodynamicPolicy[] policyByOpcodeId = new IThermodynamicPolicy[INITIAL_ARRAY_SIZE];

    /**
     * Initializes the manager with the given configuration.
     *
     * @param config The "thermodynamics" configuration block.
     */
    public ThermodynamicPolicyManager(com.typesafe.config.Config config) {
        loadPolicies(config);
    }

    /**
     * Retrieves the thermodynamic policy for the given instruction.
     * <p>
     * Optimized for performance: Uses array-based lookup by opcode ID first,
     * falling back to name/family lookup only if not cached.
     * <p>
     * Resolution order:
     * 1. Array lookup by opcode ID (fast path, O(1))
     * 2. Specific instruction name override.
     * 3. Instruction family override.
     * 4. Default policy.
     *
     * @param instruction The instruction to get the policy for.
     * @return The assigned IThermodynamicPolicy.
     */
    public IThermodynamicPolicy getPolicy(Instruction instruction) {
        // Extract opcode value from fullOpcodeId (remove TYPE_CODE bits)
        int opcodeId = instruction.getFullOpcodeId() & org.evochora.runtime.Config.VALUE_MASK;
        
        // Ensure array is large enough (grow if necessary)
        if (opcodeId >= policyByOpcodeId.length) {
            growArray(opcodeId + 1);
        }
        
        // Fast path: Direct array access (O(1))
        IThermodynamicPolicy cached = policyByOpcodeId[opcodeId];
        if (cached != null) {
            return cached;
        }

        // Slow path: Resolve policy and cache it
        IThermodynamicPolicy policy = resolvePolicy(instruction);
        
        // Cache for next time
        policyByOpcodeId[opcodeId] = policy;
        
        return policy;
    }
    
    private void growArray(int minSize) {
        // Grow to at least minSize, but double current size for efficiency
        int newSize = Math.max(minSize, policyByOpcodeId.length * 2);
        // Cap at VALUE_MASK + 1 to avoid unnecessary memory
        newSize = Math.min(newSize, org.evochora.runtime.Config.VALUE_MASK + 1);
        
        policyByOpcodeId = Arrays.copyOf(policyByOpcodeId, newSize);
        LOG.debug("Grew policy cache array to size {}", newSize);
    }
    
    private IThermodynamicPolicy resolvePolicy(Instruction instruction) {
        // 1. Check for instruction-specific policy
        IThermodynamicPolicy policy = instructionPolicies.get(instruction.getName());
        if (policy != null) {
            return policy;
        }

        // 2. Check for family-specific policy
        // We walk up the class hierarchy to find the most specific registered family
        Class<?> clazz = instruction.getClass();
        while (clazz != null && Instruction.class.isAssignableFrom(clazz)) {
            @SuppressWarnings("unchecked")
            Class<? extends Instruction> instructionClass = (Class<? extends Instruction>) clazz;
            policy = familyPolicies.get(instructionClass);
            if (policy != null) {
                return policy;
            }
            clazz = clazz.getSuperclass();
        }

        // 3. Fallback to default policy
        return defaultPolicy;
    }

    private void loadPolicies(com.typesafe.config.Config config) {
        // Load default policy
        if (config.hasPath("default")) {
            this.defaultPolicy = createPolicy(config.getConfig("default"));
            LOG.info("Loaded default thermodynamic policy: {}", this.defaultPolicy.getClass().getSimpleName());
        } else {
            throw new IllegalStateException("Missing 'default' policy configuration in runtime.thermodynamics");
        }

        if (config.hasPath("overrides")) {
            com.typesafe.config.Config overrides = config.getConfig("overrides");

            // Load instruction overrides
            if (overrides.hasPath("instructions")) {
                com.typesafe.config.Config instructionsConfig = overrides.getConfig("instructions");
                for (String key : instructionsConfig.root().keySet()) {
                    IThermodynamicPolicy policy = createPolicy(instructionsConfig.getConfig("\"" + key + "\""));
                    
                    // Support comma-separated instruction names (e.g. "ADD, SUB, MUL")
                    String[] instructionNames = key.split(",");
                    for (String name : instructionNames) {
                        String cleanName = name.trim().toUpperCase();
                        instructionPolicies.put(cleanName, policy);
                        LOG.debug("Registered policy {} for instruction {}", policy.getClass().getSimpleName(), cleanName);
                    }
                }
            }

            // Load family overrides
            if (overrides.hasPath("families")) {
                com.typesafe.config.Config familiesConfig = overrides.getConfig("families");
                for (String className : familiesConfig.root().keySet()) {
                    try {
                        Class<?> clazz = Class.forName(className);
                        if (Instruction.class.isAssignableFrom(clazz)) {
                            @SuppressWarnings("unchecked")
                            Class<? extends Instruction> familyClass = (Class<? extends Instruction>) clazz;
                            IThermodynamicPolicy policy = createPolicy(familiesConfig.getConfig("\"" + className + "\""));
                            familyPolicies.put(familyClass, policy);
                            LOG.debug("Registered policy {} for family {}", policy.getClass().getSimpleName(), className);
                        } else {
                            LOG.warn("Configured family class {} does not extend Instruction, skipping.", className);
                        }
                    } catch (ClassNotFoundException e) {
                        LOG.warn("Configured family class {} not found, skipping.", className);
                    }
                }
            }
        }
    }

    private IThermodynamicPolicy createPolicy(com.typesafe.config.Config policyConfig) {
        String className = policyConfig.getString("className");
        try {
            Class<?> clazz = Class.forName(className);
            if (!IThermodynamicPolicy.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException("Class " + className + " does not implement IThermodynamicPolicy");
            }
            Constructor<?> constructor = clazz.getConstructor();
            IThermodynamicPolicy policy = (IThermodynamicPolicy) constructor.newInstance();

            // Initialize with options if present, otherwise empty config
            com.typesafe.config.Config options = policyConfig.hasPath("options") ? policyConfig.getConfig("options") : com.typesafe.config.ConfigFactory.empty();
            policy.initialize(options);

            return policy;
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate thermodynamic policy: " + className, e);
        }
    }
}
