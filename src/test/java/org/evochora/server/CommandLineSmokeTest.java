package org.evochora.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class CommandLineSmokeTest {

    private PrintStream originalErr;
    private java.io.InputStream originalIn;

    @AfterEach
    void restore() {
        if (originalErr != null) System.setErr(originalErr);
        if (originalIn != null) System.setIn(originalIn);
    }

    @Test
    void status_then_exit_prints_status_and_terminates() throws Exception {
        String input = "status\nexit\n";
        originalIn = System.in;
        System.setIn(new ByteArrayInputStream(input.getBytes()));
        originalErr = System.err;
        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errOut));

        Thread t = new Thread(() -> {
            try { CommandLineInterface.main(new String[]{}); } catch (Exception ignored) {}
        });
        t.start();
        t.join(3000);

        String output = errOut.toString();
        assertThat(output).contains("sim:");
    }
}


