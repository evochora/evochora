package org.evochora.server;

import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommandLineInterfaceTest {

	@Test
	void persistenceService_createsRunDbFileOnStart() {
		ITickMessageQueue q = new InMemoryTickQueue();
		PersistenceService persist = new PersistenceService(q);
		persist.start();
		Path db = persist.getDbFilePath();
		assertThat(db).isNotNull();
		assertThat(Files.exists(db)).isTrue();
		persist.shutdown();
	}
}


