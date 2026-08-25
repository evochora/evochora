package org.evochora.runtime.thermodynamics;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
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

    private final IThermodynamicPolicy defaultPolicy;
    private final Map<String, IThermodynamicPolicy> instructionPolicies = new HashMap<>();
    private final Map<Class<? extends Instruction>, IThermodynamicPolicy> familyPolicies = new HashMap<>();

    /**
     * Policy per opcode id, sized once for the whole instruction set.
     * <p>
     * Instructions execute concurrently in the first wave of a tick, so this array is written from
     * several threads. Three properties make that safe without synchronisation, and all three are
     * needed:
     * <ul>
     *   <li>The field is final, so the array reference is visible to every thread once the
     *       constructor completes. An array that grew on demand would lose this: replacing the
     *       reference is not idempotent, and a thread could index the shorter array it had read
     *       before another thread enlarged it.</li>
     *   <li>A slot only ever receives a policy that {@link #resolvePolicy} took from a final field
     *       of this class — the two override maps or the default. Nothing is constructed on the
     *       lookup path, so two threads filling the same slot write the same reference.</li>
     *   <li>The manager itself is held in a final field by its owner, so the policies it hands out
     *       are fully constructed for every thread that reaches them. Publishing a manager unsafely
     *       would break that, whatever this field does.</li>
     * </ul>
     * <p>
     * The array covers every opcode registered when the manager is constructed. Registering further
     * instructions afterwards would leave it too small — the instruction set is expected to be
     * complete before any simulation is built.
     */
    private final IThermodynamicPolicy[] policyByOpcodeId;

    /**
     * Initializes the manager with the given configuration.
     * <p>
     * The instruction set must be registered before a manager is built, because the policy array is
     * sized to cover every registered opcode.
     *
     * @param config The "thermodynamics" configuration block.
     * @throws IllegalStateException if no instruction is registered yet
     */
    public ThermodynamicPolicyManager(com.typesafe.config.Config config) {
        this.defaultPolicy = loadPolicies(config);
        this.policyByOpcodeId = new IThermodynamicPolicy[highestOpcodeId() + 1];
    }

    /**
     * The largest opcode id any instruction can carry into {@link #getPolicy}.
     * <p>
     * Taken from the registered instruction set rather than from a fixed bound, so the array matches
     * what actually exists.
     */
    private static int highestOpcodeId() {
        List<Instruction.InstructionInfo> registered = Instruction.getInstructionSetInfo();
        if (registered.isEmpty()) {
            // Sizing the array against an empty registry would produce one too small for every
            // instruction, and the mismatch would only show as a bare index error deep in a tick.
            throw new IllegalStateException(
                    "No instructions are registered; the instruction set must be initialised "
                    + "before a ThermodynamicPolicyManager is built");
        }
        int highest = 0;
        for (Instruction.InstructionInfo info : registered) {
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

    /**
     * Loads the configured policies into the override maps and returns the default one.
     * <p>
     * The default is returned rather than assigned here, so that the constructor can hold it in a
     * final field — which is what lets threads read policies without synchronisation.
     *
     * @param config the "thermodynamics" configuration block
     * @return the policy to use where no override applies
     */
    private IThermodynamicPolicy loadPolicies(com.typesafe.config.Config config) {
        if (!config.hasPath("default")) {
            throw new IllegalStateException("Missing 'default' policy configuration in runtime.thermodynamics");
        }
        IThermodynamicPolicy loadedDefault = createPolicy(config.getConfig("default"));
        LOG.info("Loaded default thermodynamic policy: {}", loadedDefault.getClass().getSimpleName());

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
        return loadedDefault;
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
