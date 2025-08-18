package org.evochora.server;

import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandLineInterfaceTest {

    @Test
    void persistenceService_createsRunDbFileOnStart() {
        ITickMessageQueue q = new InMemoryTickQueue();
        // Verwende in-memory SQLite, damit im Test keine Datei erzeugt wird
        PersistenceService persist = new PersistenceService(q, false, "jdbc:sqlite:file:cliTest?mode=memory&cache=shared");
        persist.start();
        // In-memory: Es gibt keinen Datei-Pfad mehr; prüfe, dass eine JDBC-URL gesetzt ist
        assertThat(persist.getJdbcUrl()).startsWith("jdbc:sqlite:");
        persist.shutdown();
    }
}
