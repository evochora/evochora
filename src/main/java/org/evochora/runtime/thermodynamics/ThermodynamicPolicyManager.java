package org.evochora.runtime.thermodynamics;

import java.lang.reflect.Constructor;
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

    private IThermodynamicPolicy defaultPolicy;
    private final Map<String, IThermodynamicPolicy> instructionPolicies = new HashMap<>();
    private final Map<Class<? extends Instruction>, IThermodynamicPolicy> familyPolicies = new HashMap<>();

    /**
     * Policy per opcode id, sized once for the whole instruction set.
     * <p>
     * Instructions execute concurrently in the first wave of a tick, so this array is written from
     * several threads. It is safe without synchronisation because of two properties, and only
     * because of both: the field is final, so the reference is visible to every thread once the
     * constructor completes; and a slot is only ever filled with a policy that already existed
     * before this manager was published, since {@link #resolvePolicy} hands out instances created
     * during construction rather than making new ones. Two threads filling the same slot therefore
     * write the same reference to an object no thread can see half-built.
     * <p>
     * An array that grew on demand would break the first property: replacing the reference is not
     * idempotent, and a thread could index the shorter array it read before another thread enlarged
     * it.
     */
    private final IThermodynamicPolicy[] policyByOpcodeId;

    /**
     * Initializes the manager with the given configuration.
     *
     * @param config The "thermodynamics" configuration block.
     */
    public ThermodynamicPolicyManager(com.typesafe.config.Config config) {
        loadPolicies(config);
        this.policyByOpcodeId = new IThermodynamicPolicy[highestOpcodeId() + 1];
    }

    /**
     * The largest opcode id any instruction can carry into {@link #getPolicy}.
     * <p>
     * Taken from the registered instruction set rather than from a fixed bound, so the array matches
     * what actually exists.
     */
    private static int highestOpcodeId() {
        int highest = 0;
        for (Instruction.InstructionInfo info : Instruction.getInstructionSetInfo()) {
            highest = Math.max(highest, info.opcodeId());
        }
        return highest;
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

        // No bounds check: an instruction only exists if its opcode is registered — the virtual
        // machine rejects anything else before it gets this far — and the array covers every
        // registered opcode.
        IThermodynamicPolicy cached = policyByOpcodeId[opcodeId];
        if (cached != null) {
            return cached;
        }

        IThermodynamicPolicy policy = resolvePolicy(instruction);
        policyByOpcodeId[opcodeId] = policy;

        return policy;
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
