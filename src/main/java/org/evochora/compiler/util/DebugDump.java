package org.evochora.compiler.util;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrInstruction;
import org.evochora.compiler.ir.IrItem;
import org.evochora.compiler.ir.IrLabelDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Utility class for dumping debug information during compilation.
 */
public final class DebugDump {

	private DebugDump() {}

	/**
	 * Dumps the contents of a {@link ProgramArtifact} to files in the build directory.
	 * @param programName The name of the program, used for creating the dump directory.
	 * @param artifact The program artifact to dump.
	 */
	public static void dumpProgramArtifact(String programName, ProgramArtifact artifact) {
		Path root = Path.of("build", "compiler-dumps", sanitize(programName));
		try {
			Files.createDirectories(root);
			// machine code (sorted by linear address)
			Path code = root.resolve("machine_code.txt");
			StringBuilder sb = new StringBuilder();
            artifact.linearAddressToCoord().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
					.forEach(e -> sb.append(e.getKey()).append(": ")
							.append(java.util.Arrays.toString(e.getValue()))
							.append(" -> ")
							.append(artifact.machineCodeLayout().get(e.getValue()))
							.append('\n'));
			Files.writeString(code, sb.toString());

			// labels
			Path labels = root.resolve("labels.txt");
			StringBuilder sb2 = new StringBuilder();
			artifact.labelAddressToName().entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.forEach(e -> sb2.append(e.getKey()).append(" -> ").append(e.getValue()).append('\n'));
			Files.writeString(labels, sb2.toString());
		} catch (IOException ignored) {}
	}

	/**
	 * Dumps the a list of IR items to a file.
	 * @param programName The name of the program, used for creating the dump directory.
	 * @param phase The name of the compilation phase, used for the file name.
	 * @param items The list of IR items to dump.
	 */
	public static void dumpIr(String programName, String phase, List<IrItem> items) {
		Path root = Path.of("build", "compiler-dumps", sanitize(programName));
		try {
			Files.createDirectories(root);
			Path f = root.resolve(phase + "_ir.txt");
			StringBuilder sb = new StringBuilder();
			for (IrItem it : items) {
				if (it instanceof IrInstruction ins) sb.append("INS ").append(ins.opcode());
				else if (it instanceof IrDirective d) sb.append("DIR ").append(d.namespace()).append(':').append(d.name());
				else if (it instanceof IrLabelDef l) sb.append("LBL ").append(l.name());
				sb.append('\n');
			}
			Files.writeString(f, sb.toString());
		} catch (IOException ignored) {}
	}

	private static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9_.-]", "_"); }
}


