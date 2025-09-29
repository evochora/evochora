package org.evochora.cli;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class CliIntegrationTest {

    private static final String PID_FILE_NAME = ".evochora.pid";
    private Thread nodeThread;

    @BeforeEach
    public void setUp() {
        // Clean up PID file before each test
        File pidFile = new File(PID_FILE_NAME);
        if (pidFile.exists()) {
            pidFile.delete();
        }
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        // Ensure the node thread is stopped if it's still running
        if (nodeThread != null && nodeThread.isAlive()) {
            nodeThread.interrupt();
            nodeThread.join(5000); // Wait for it to die
        }
        // Clean up PID file after each test
        File pidFile = new File(PID_FILE_NAME);
        if (pidFile.exists()) {
            pidFile.delete();
        }
    }

    @Test
    public void testNodeLifecycleWithPidFile() throws InterruptedException {
        CommandLineInterface cli = new CommandLineInterface();
        CommandLine cmd = new CommandLine(cli);
        File pidFile = new File(PID_FILE_NAME);

        // 1. Start the node in a background thread using 'run -d'
        // We run the command in a thread because node.start() is blocking.
        nodeThread = new Thread(() -> cmd.execute("node", "run", "-d"));
        nodeThread.start();

        // 2. Wait for the server to be ready and for the PID file to be created
        await().atMost(15, TimeUnit.SECONDS).until(() -> {
            try {
                // Check if server is responsive
                RestAssured.given().port(8080).get("/pipeline/api/status").then().statusCode(200);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(pidFile.exists(), "PID file should be created in detached mode");

        // 3. Stop the node using the CLI stop command
        int stopExitCode = cmd.execute("node", "stop");
        assertEquals(0, stopExitCode, "Stop command should execute successfully");

        // 4. Verify the node thread has terminated and PID file is deleted
        nodeThread.join(5000); // Wait for the thread to finish
        assertFalse(nodeThread.isAlive(), "Node thread should have terminated after stop command.");
        assertFalse(pidFile.exists(), "PID file should be deleted after stopping the node");
    }
}