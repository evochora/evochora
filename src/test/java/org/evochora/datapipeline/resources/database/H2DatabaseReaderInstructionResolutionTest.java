package org.evochora.datapipeline.resources.database;

import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.*;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests instruction resolution in H2DatabaseReader.
 * <p>
 * Tests various argument types (REGISTER, IMMEDIATE, VECTOR) and "next" instruction resolution.
 * <p>
 * Note: STACK operands are not shown in the instruction view, as they are not encoded in the machine code
 * (they are taken from the stack at runtime).
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseReaderInstructionResolutionTest {

    private H2Database database;
    private IDatabaseReaderProvider provider;
    private String runId;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        String dbUrl = "jdbc:h2:mem:test-instruction-resolution-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        com.typesafe.config.Config dbConfig = ConfigFactory.parseString(
            "jdbcUrl = \"" + dbUrl + "\"\n" +
            "username = \"sa\"\n" +
            "password = \"\"\n" +
            "maxPoolSize = 5\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy\"\n" +
            "  options { compression { enabled = false } }\n" +
            "}\n"
        );
        database = new H2Database("test-db", dbConfig);
        provider = database;
        runId = "test-run-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void resolveInstruction_REGISTER_argument() throws Exception {
        setupDatabase();
        
        // SETI %DR0, DATA:42 - REGISTER argument
        int setiOpcode = Instruction.getInstructionIdByName("SETI") | org.evochora.runtime.Config.TYPE_CODE;
        int regArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt(); // %DR0
        int immArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 42).toInt();

        writeOrganismWithInstruction(1L, 1, setiOpcode, java.util.List.of(regArg, immArg), 5);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(1L, 1);

            assertThat(details.state.instructions.last).isNotNull();
            assertThat(details.state.instructions.last.opcodeName).isEqualTo("SETI");
            assertThat(details.state.instructions.last.arguments).hasSize(2);
            assertThat(details.state.instructions.last.arguments.get(0).type).isEqualTo("REGISTER");
            assertThat(details.state.instructions.last.arguments.get(0).registerId).isEqualTo(0);
            assertThat(details.state.instructions.last.arguments.get(0).registerValue).isNotNull();
            assertThat(details.state.instructions.last.arguments.get(1).type).isEqualTo("IMMEDIATE");
        }
    }

    @Test
    void resolveInstruction_STACK_argument() throws Exception {
        setupDatabase();
        
        // ADDS (stack, stack) - STACK arguments are not in code, so they don't appear in instruction view
        int addsOpcode = Instruction.getInstructionIdByName("ADDS") | org.evochora.runtime.Config.TYPE_CODE;
        // ADDS has no raw arguments (both operands from stack at runtime, not encoded in code)

        writeOrganismWithInstruction(1L, 1, addsOpcode, java.util.List.of(), 3);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(1L, 1);

            assertThat(details.state.instructions.last).isNotNull();
            assertThat(details.state.instructions.last.opcodeName).isEqualTo("ADDS");
            // STACK operands are not in code, so they don't appear in the instruction view
            // The instruction view shows only what's actually encoded in the machine code
            assertThat(details.state.instructions.last.arguments).isEmpty();
            assertThat(details.state.instructions.last.argumentTypes).isEmpty();
        }
    }

    @Test
    void resolveInstruction_VECTOR_argument() throws Exception {
        setupDatabase();
        
        // SETV %DR0, [1,2] - VECTOR argument
        int setvOpcode = Instruction.getInstructionIdByName("SETV") | org.evochora.runtime.Config.TYPE_CODE;
        int regArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt();
        int vecX = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 1).toInt();
        int vecY = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 2).toInt();

        writeOrganismWithInstruction(1L, 1, setvOpcode, java.util.List.of(regArg, vecX, vecY), 5);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(1L, 1);

            assertThat(details.state.instructions.last).isNotNull();
            assertThat(details.state.instructions.last.opcodeName).isEqualTo("SETV");
            assertThat(details.state.instructions.last.arguments).hasSize(2);
            assertThat(details.state.instructions.last.arguments.get(0).type).isEqualTo("REGISTER");
            assertThat(details.state.instructions.last.arguments.get(1).type).isEqualTo("VECTOR");
            assertThat(details.state.instructions.last.arguments.get(1).components).isEqualTo(new int[]{1, 2});
        }
    }

    @Test
    void resolveInstruction_LOCATION_REGISTER_argument() throws Exception {
        setupDatabase();
        
        // DPLR %LR0 - LOCATION_REGISTER argument
        int dplrOpcode = Instruction.getInstructionIdByName("DPLR") | org.evochora.runtime.Config.TYPE_CODE;
        int lrArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt(); // %LR0

        writeOrganismWithInstructionAndLocationRegisters(1L, 1, dplrOpcode, java.util.List.of(lrArg), 3,
                new int[][]{{5, 6}, {7, 8}, null, null}); // LR0=[5,6], LR1=[7,8], LR2/LR3=null

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(1L, 1);

            assertThat(details.state.instructions.last).isNotNull();
            assertThat(details.state.instructions.last.opcodeName).isEqualTo("DPLR");
            assertThat(details.state.instructions.last.arguments).hasSize(1);
            assertThat(details.state.instructions.last.arguments.get(0).type).isEqualTo("REGISTER");
            assertThat(details.state.instructions.last.arguments.get(0).registerId).isEqualTo(0);
            assertThat(details.state.instructions.last.arguments.get(0).registerType).isEqualTo("LR");
            assertThat(details.state.instructions.last.arguments.get(0).registerValue).isNotNull();
            assertThat(details.state.instructions.last.arguments.get(0).registerValue.kind).isEqualTo(org.evochora.datapipeline.api.resources.database.dto.RegisterValueView.Kind.VECTOR);
            assertThat(details.state.instructions.last.arguments.get(0).registerValue.vector).isEqualTo(new int[]{5, 6});
        }
    }

    @Test
    void resolveNextInstruction_whenSamplingIntervalIsOne() throws Exception {
        setupDatabase();
        
        int setiOpcode = Instruction.getInstructionIdByName("SETI") | org.evochora.runtime.Config.TYPE_CODE;
        int regArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt();
        int immArg1 = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 10).toInt();
        int immArg2 = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 20).toInt();

        // Write tick 1
        writeOrganismWithInstruction(1L, 1, setiOpcode, java.util.List.of(regArg, immArg1), 5);
        // Write tick 2
        writeOrganismWithInstruction(2L, 1, setiOpcode, java.util.List.of(regArg, immArg2), 5);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(1L, 1);

            assertThat(details.state.instructions.last).isNotNull();
            assertThat(details.state.instructions.last.opcodeName).isEqualTo("SETI");
            assertThat(details.state.instructions.next).isNotNull();
            assertThat(details.state.instructions.next.opcodeName).isEqualTo("SETI");
            assertThat(details.state.instructions.next.arguments.get(1).value).isEqualTo(20);
        }
    }

    @Test
    void resolveNextInstruction_whenSamplingIntervalNotOne_returnsNull() throws Exception {
        setupDatabaseWithSamplingInterval(2);
        
        int setiOpcode = Instruction.getInstructionIdByName("SETI") | org.evochora.runtime.Config.TYPE_CODE;
        int regArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt();
        int immArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 10).toInt();

        writeOrganismWithInstruction(1L, 1, setiOpcode, java.util.List.of(regArg, immArg), 5);

        try (IDatabaseReader reader = provider.createReader(runId)) {
            OrganismTickDetails details = reader.readOrganismDetails(1L, 1);

            assertThat(details.state.instructions.last).isNotNull();
            assertThat(details.state.instructions.next).isNull(); // sampling_interval != 1
        }
    }

    private void setupDatabase() throws Exception {
        setupDatabaseWithSamplingInterval(1);
    }

    private void setupDatabaseWithSamplingInterval(int samplingInterval) throws Exception {
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            String schemaName = "SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            conn.createStatement().execute("SET SCHEMA \"" + schemaName + "\"");

            // Create metadata table and insert metadata
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" TEXT)");
            SimulationMetadata metadata = SimulationMetadata.newBuilder()
                    .setSimulationRunId(runId)
                    .setEnvironment(EnvironmentConfig.newBuilder()
                            .setDimensions(2)
                            .addShape(10)
                            .addToroidal(false)
                            .addShape(10)
                            .addToroidal(false)
                            .build())
                    .setStartTimeMs(System.currentTimeMillis())
                    .setInitialSeed(42L)
                    .setSamplingInterval(samplingInterval)
                    .build();
            String metadataJson = org.evochora.datapipeline.utils.protobuf.ProtobufConverter.toJson(metadata);
            conn.createStatement().execute("INSERT INTO metadata (\"key\", \"value\") VALUES ('full_metadata', '" +
                    metadataJson.replace("'", "''") + "')");

            // Create organism tables
            database.doCreateOrganismTables(conn);
            conn.commit();
        }
    }

    private void writeOrganismWithInstruction(long tickNumber, int organismId, int opcodeId,
                                             java.util.List<Integer> rawArguments, int energyCost) throws Exception {
        writeOrganismWithInstructionAndLocationRegisters(tickNumber, organismId, opcodeId, rawArguments, energyCost, null);
    }

    private void writeOrganismWithInstructionAndLocationRegisters(long tickNumber, int organismId, int opcodeId,
                                                                  java.util.List<Integer> rawArguments, int energyCost,
                                                                  int[][] locationRegisters) throws Exception {
        Object connObj = database.acquireDedicatedConnection();
        try (Connection conn = (Connection) connObj) {
            String schemaName = "SIM_" + runId.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);

            Vector ipBeforeFetch = Vector.newBuilder().addComponents(1).addComponents(2).build();
            Vector dvBeforeFetch = Vector.newBuilder().addComponents(0).addComponents(1).build();

            OrganismState.Builder orgBuilder = OrganismState.newBuilder()
                    .setOrganismId(organismId)
                    .setBirthTick(0)
                    .setProgramId("prog-" + organismId)
                    .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
                    .setEnergy(100)
                    .setIp(Vector.newBuilder().addComponents(1).addComponents(2).build())
                    .setDv(Vector.newBuilder().addComponents(0).addComponents(1).build())
                    .addDataPointers(Vector.newBuilder().addComponents(5).addComponents(5).build())
                    .setActiveDpIndex(0)
                    .addDataRegisters(RegisterValue.newBuilder().setScalar(42).build())
                    .setInstructionOpcodeId(opcodeId)
                    .setInstructionEnergyCost(energyCost)
                    .setIpBeforeFetch(ipBeforeFetch)
                    .setDvBeforeFetch(dvBeforeFetch);

            for (Integer arg : rawArguments) {
                orgBuilder.addInstructionRawArguments(arg);
            }

            // Add location registers if provided
            if (locationRegisters != null) {
                for (int[] lr : locationRegisters) {
                    if (lr != null) {
                        Vector.Builder lrBuilder = Vector.newBuilder();
                        for (int component : lr) {
                            lrBuilder.addComponents(component);
                        }
                        orgBuilder.addLocationRegisters(lrBuilder.build());
                    } else {
                        // Add empty vector for null entries (to maintain index alignment)
                        orgBuilder.addLocationRegisters(Vector.newBuilder().build());
                    }
                }
            }
            
            // Add register values before execution (required for annotation display)
            // Extract register IDs from rawArguments based on instruction signature
            java.util.Optional<org.evochora.runtime.isa.InstructionSignature> signatureOpt =
                    org.evochora.runtime.isa.Instruction.getSignatureById(opcodeId);
            if (signatureOpt.isPresent()) {
                org.evochora.runtime.isa.InstructionSignature signature = signatureOpt.get();
                java.util.List<org.evochora.runtime.isa.InstructionArgumentType> argTypes = signature.argumentTypes();
                int argIndex = 0;
                
                for (org.evochora.runtime.isa.InstructionArgumentType argType : argTypes) {
                    if (argType == org.evochora.runtime.isa.InstructionArgumentType.REGISTER ||
                        argType == org.evochora.runtime.isa.InstructionArgumentType.LOCATION_REGISTER) {
                        if (argIndex < rawArguments.size()) {
                            int rawArg = rawArguments.get(argIndex);
                            org.evochora.runtime.model.Molecule molecule = org.evochora.runtime.model.Molecule.fromInt(rawArg);
                            int registerId = molecule.toScalarValue();
                            
                            // Get register value before execution from current registers
                            if (argType == org.evochora.runtime.isa.InstructionArgumentType.REGISTER) {
                                // DR/PR/FPR register - get from dataRegisters
                                if (registerId == 0 && orgBuilder.getDataRegistersCount() > 0) {
                                    orgBuilder.putInstructionRegisterValuesBefore(registerId, orgBuilder.getDataRegisters(0));
                                }
                            } else {
                                // LOCATION_REGISTER - get from locationRegisters
                                if (locationRegisters != null && registerId >= 0 && registerId < locationRegisters.length) {
                                    int[] lr = locationRegisters[registerId];
                                    if (lr != null) {
                                        Vector.Builder lrBuilder = Vector.newBuilder();
                                        for (int component : lr) {
                                            lrBuilder.addComponents(component);
                                        }
                                        orgBuilder.putInstructionRegisterValuesBefore(registerId,
                                                RegisterValue.newBuilder().setVector(lrBuilder.build()).build());
                                    }
                                }
                            }
                            
                            argIndex++;
                        }
                    } else if (argType == org.evochora.runtime.isa.InstructionArgumentType.VECTOR ||
                               argType == org.evochora.runtime.isa.InstructionArgumentType.LABEL) {
                        // VECTOR/LABEL have no register arguments encoded in rawArgs
                    } else {
                        // IMMEDIATE, LITERAL - no register arguments
                        argIndex++;
                    }
                }
            }

            TickData tick = TickData.newBuilder()
                    .setTickNumber(tickNumber)
                    .setSimulationRunId(runId)
                    .addOrganisms(orgBuilder.build())
                    .build();

            database.doWriteOrganismStates(conn, java.util.List.of(tick));
            conn.commit();
        }
    }
}

