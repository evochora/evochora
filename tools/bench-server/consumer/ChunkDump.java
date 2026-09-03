package org.evochora.bench;

import java.nio.file.Files;
import java.nio.file.Path;

import org.evochora.datapipeline.api.contracts.TickDataChunk;

import com.google.protobuf.TextFormat;

/**
 * Decodes one dumped TickDataChunk file (normalized bytes as written by the
 * forensic TickHashConsumer) into protobuf text format on stdout.
 */
public final class ChunkDump {
    public static void main(String[] args) throws Exception {
        TickDataChunk chunk = TickDataChunk.parseFrom(Files.readAllBytes(Path.of(args[0])));
        System.out.println(TextFormat.printer().printToString(chunk));
    }
}
