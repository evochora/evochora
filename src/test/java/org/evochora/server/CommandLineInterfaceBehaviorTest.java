package org.evochora.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommandLineInterfaceBehaviorTest {

    private PrintStream originalErr;
    private java.io.InputStream originalIn;

    @AfterEach
    void restore() {
        if (originalErr != null) System.setErr(originalErr);
        if (originalIn != null) System.setIn(originalIn);
    }

    private String runCliWithInput(String input) throws Exception {
        originalIn = System.in;
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        originalErr = System.err;
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errOut));

        Thread t = new Thread(() -> {
            try { CommandLineInterface.main(new String[]{}); } catch (Exception ignored) {}
        });
        t.start();
        t.join(4000);
        return errOut.toString(StandardCharsets.UTF_8);
    }

    private Path writeTempConfig(String content) throws IOException {
        Path tmp = Files.createTempFile("evo_cfg_", ".json");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    @Test
    void config_command_success_parses_and_reports_shape() throws Exception {
        String cfg = "{\n" +
                "  \"environment\": { \"shape\": [10, 8], \"toroidal\": true },\n" +
                "  \"organisms\": []\n" +
                "}\n";
        Path cfgPath = writeTempConfig(cfg);
        String input = "config " + cfgPath.toAbsolutePath() + "\nexit\n";
        String out = runCliWithInput(input);
        assertThat(out).contains("Loaded config from");
        assertThat(out).contains("dims=2");
        assertThat(out).contains("toroidal=true");
    }

    @Test
    void config_command_failure_reports_error() throws Exception {
        String input = "config C:/no/such/file.json\nexit\n";
        String out = runCliWithInput(input);
        assertThat(out).contains("Failed to load config");
    }

    @Test
    void run_command_after_config_starts_services() throws Exception {
        // Use a small config with one organism referencing a bundled resource
        String cfg = "{\n" +
                "  \"environment\": { \"shape\": [10, 10], \"toroidal\": true },\n" +
                "  \"organisms\": [ {\n" +
                "    \"id\": \"t1\",\n" +
                "    \"program\": \"prototypes/InstructionTester.s\",\n" +
                "    \"initialEnergy\": 500,\n" +
                "    \"placement\": { \"strategy\": \"fixed\", \"positions\": [[1,1]] }\n" +
                "  } ]\n" +
                "}\n";
        Path cfgPath = writeTempConfig(cfg);
        String input = "config " + cfgPath.toAbsolutePath() + "\nrun debug\nstatus\nexit\n";
        String out = runCliWithInput(input);
        assertThat(out).contains("SimulationEngine started.");
        assertThat(out).contains("PersistenceService started.");
        // No default-config warning should appear when a config was explicitly provided
        assertThat(out).doesNotContain("No config provided. Using default resources config.json.");
    }

    @Test
    void load_command_removed() throws Exception {
        String input = "load main.s\nexit\n";
        String out = runCliWithInput(input);
        assertThat(out).contains("Unknown command");
    }
}


