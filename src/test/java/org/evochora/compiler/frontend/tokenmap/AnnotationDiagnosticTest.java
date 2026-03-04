package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.CompilerTestBase;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class AnnotationDiagnosticTest extends CompilerTestBase {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void diagnoseRealCompilation() throws Exception {
        String assemblyRoot = Path.of("").toAbsolutePath().resolve("assembly/primordial").toString();
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(assemblyRoot, null)));

        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile("main.evo", testEnvProps, options);

        String mainFileName = artifact.sources().keySet().stream()
            .filter(k -> k.endsWith("main.evo"))
            .findFirst().orElse(null);
        assertNotNull(mainFileName, "main.evo must be in sources");

        // Print ALL main file tokens organized by line
        System.out.println("=== MAIN FILE TOKENS BY LINE ===");
        Map<Integer, Map<Integer, List<TokenInfo>>> lineMap = artifact.tokenLookup().get(mainFileName);
        assertNotNull(lineMap, "tokenLookup must have entry for main file");

        String[] fileLines = artifact.sources().get(mainFileName).toArray(new String[0]);
        TreeMap<Integer, Map<Integer, List<TokenInfo>>> sortedLineMap = new TreeMap<>(lineMap);

        int totalTokens = 0;
        for (Map.Entry<Integer, Map<Integer, List<TokenInfo>>> lineEntry : sortedLineMap.entrySet()) {
            int lineNumber = lineEntry.getKey();
            String sourceLine = (lineNumber - 1 < fileLines.length) ? fileLines[lineNumber - 1] : "<no line>";
            System.out.println("  Line " + lineNumber + ": " + sourceLine.trim());

            TreeMap<Integer, List<TokenInfo>> sortedColMap = new TreeMap<>(lineEntry.getValue());
            for (Map.Entry<Integer, List<TokenInfo>> colEntry : sortedColMap.entrySet()) {
                for (TokenInfo ti : colEntry.getValue()) {
                    totalTokens++;
                    System.out.println("    col=" + colEntry.getKey()
                        + " text='" + ti.tokenText() + "'"
                        + " type=" + ti.tokenType()
                        + " scope=" + ti.scope()
                        + " qualified=" + ti.qualifiedName());
                }
            }
        }
        System.out.println("  Total tokens: " + totalTokens);

        // Simulate protobuf serialization for a few tokens
        System.out.println("\n=== PROTOBUF SIMULATION (tokenType.name()) ===");
        artifact.tokenMap().entrySet().stream()
            .filter(e -> e.getKey().fileName().equals(mainFileName))
            .limit(10)
            .forEach(e -> {
                TokenInfo ti = e.getValue();
                String serializedType = ti.tokenType().name();
                String serializedScope = ti.scope();
                System.out.println("  text='" + ti.tokenText() + "'"
                    + " serializedType='" + serializedType + "'"
                    + " serializedScope='" + serializedScope + "'"
                    + " frontendMatch: ALIAS=" + "ALIAS".equals(serializedType)
                    + " LABEL=" + "LABEL".equals(serializedType)
                    + " PROCEDURE=" + "PROCEDURE".equals(serializedType)
                    + " VARIABLE=" + "VARIABLE".equals(serializedType)
                    + " CONSTANT=" + "CONSTANT".equals(serializedType));
            });

        // Check register alias lookup simulation (mimics frontend resolveToCanonicalRegister)
        System.out.println("\n=== REGISTER ALIAS LOOKUP SIMULATION ===");
        artifact.tokenMap().entrySet().stream()
            .filter(e -> e.getKey().fileName().equals(mainFileName))
            .filter(e -> e.getValue().tokenType() == org.evochora.compiler.api.TokenKind.ALIAS)
            .forEach(e -> {
                TokenInfo ti = e.getValue();
                // Frontend does: lookupKey = (qualifiedName || token).toUpperCase()
                String qualifiedName = ti.qualifiedName(); // null - not in protobuf
                String tokenText = ti.tokenText();
                String lookupKey = (qualifiedName != null ? qualifiedName : tokenText).toUpperCase();
                Integer regId = artifact.registerAliasMap().get(lookupKey);
                System.out.println("  token='" + tokenText + "'"
                    + " qualifiedName=" + qualifiedName
                    + " lookupKey='" + lookupKey + "'"
                    + " regId=" + regId
                    + " found=" + (regId != null));
            });

        // Check label lookup simulation (mimics frontend resolveLabelNameToHash)
        System.out.println("\n=== LABEL LOOKUP SIMULATION ===");
        artifact.tokenMap().entrySet().stream()
            .filter(e -> e.getKey().fileName().equals(mainFileName))
            .filter(e -> e.getValue().tokenType() == org.evochora.compiler.api.TokenKind.LABEL
                      || e.getValue().tokenType() == org.evochora.compiler.api.TokenKind.PROCEDURE)
            .forEach(e -> {
                TokenInfo ti = e.getValue();
                String qualifiedName = ti.qualifiedName(); // potentially null
                String tokenText = ti.tokenText();
                String lookupKey = (qualifiedName != null ? qualifiedName : tokenText).toUpperCase();
                Integer hash = artifact.labelNameToValue().get(lookupKey);
                System.out.println("  token='" + tokenText + "'"
                    + " type=" + ti.tokenType()
                    + " qualifiedName=" + qualifiedName
                    + " lookupKey='" + lookupKey + "'"
                    + " hash=" + hash
                    + " found=" + (hash != null));
            });

        // Position verification
        System.out.println("\n=== POSITION VERIFICATION ===");
        int matchCount = 0;
        int mismatchCount = 0;
        for (Map.Entry<Integer, Map<Integer, List<TokenInfo>>> lineEntry : lineMap.entrySet()) {
            int lineNumber = lineEntry.getKey();
            for (Map.Entry<Integer, List<TokenInfo>> colEntry : lineEntry.getValue().entrySet()) {
                int absColumn = colEntry.getKey();
                for (TokenInfo token : colEntry.getValue()) {
                    int offset = 0;
                    for (int i = 0; i < lineNumber - 1 && i < fileLines.length; i++) {
                        offset += fileLines[i].length() + 1;
                    }
                    int relColumn = absColumn - 1 - offset;
                    String line = (lineNumber - 1 < fileLines.length) ? fileLines[lineNumber - 1] : "";
                    boolean match = relColumn >= 0 && relColumn < line.length()
                        && line.substring(relColumn).startsWith(token.tokenText());
                    if (match) matchCount++;
                    else mismatchCount++;
                }
            }
        }
        System.out.println("  Matches: " + matchCount + ", Mismatches: " + mismatchCount);

        // Protobuf serialization roundtrip for tokenLookup
        System.out.println("\n=== PROTOBUF SERIALIZATION ROUNDTRIP ===");
        // Build protobuf FileTokenLookup for main file (same as SimulationEngine.convertProgramArtifact)
        org.evochora.datapipeline.api.contracts.FileTokenLookup.Builder ftlBuilder =
                org.evochora.datapipeline.api.contracts.FileTokenLookup.newBuilder()
                        .setFileName(mainFileName);

        lineMap.forEach((ln, colMap) -> {
            org.evochora.datapipeline.api.contracts.LineTokenLookup.Builder ltlBuilder =
                    org.evochora.datapipeline.api.contracts.LineTokenLookup.newBuilder()
                            .setLineNumber(ln);
            colMap.forEach((col, tokens) -> {
                org.evochora.datapipeline.api.contracts.ColumnTokenLookup.Builder ctlBuilder =
                        org.evochora.datapipeline.api.contracts.ColumnTokenLookup.newBuilder()
                                .setColumnNumber(col);
                for (TokenInfo ti : tokens) {
                    ctlBuilder.addTokens(org.evochora.datapipeline.api.contracts.TokenInfo.newBuilder()
                            .setTokenText(ti.tokenText())
                            .setTokenType(ti.tokenType().name())
                            .setScope(ti.scope())
                            .build());
                }
                ltlBuilder.addColumns(ctlBuilder.build());
            });
            ftlBuilder.addLines(ltlBuilder.build());
        });

        org.evochora.datapipeline.api.contracts.FileTokenLookup protoFtl = ftlBuilder.build();

        // Convert to JSON (same as ProtobufConverter.toJson)
        String json = com.google.protobuf.util.JsonFormat.printer().print(protoFtl);

        // Parse with Jackson to see exactly what the frontend gets
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(json);

        System.out.println("  fileName: " + jsonNode.get("fileName"));
        com.fasterxml.jackson.databind.JsonNode lines = jsonNode.get("lines");
        System.out.println("  lines type: " + (lines != null ? lines.getNodeType() : "null"));
        System.out.println("  lines count: " + (lines != null ? lines.size() : 0));

        // Check first line entry
        if (lines != null && lines.size() > 0) {
            com.fasterxml.jackson.databind.JsonNode firstLine = lines.get(0);
            System.out.println("  First line entry: lineNumber=" + firstLine.get("lineNumber"));
            com.fasterxml.jackson.databind.JsonNode cols = firstLine.get("columns");
            if (cols != null && cols.size() > 0) {
                com.fasterxml.jackson.databind.JsonNode firstCol = cols.get(0);
                System.out.println("  First col entry: columnNumber=" + firstCol.get("columnNumber"));
                com.fasterxml.jackson.databind.JsonNode tokensArr = firstCol.get("tokens");
                if (tokensArr != null && tokensArr.size() > 0) {
                    com.fasterxml.jackson.databind.JsonNode firstToken = tokensArr.get(0);
                    System.out.println("  First token JSON: " + firstToken.toString());
                    System.out.println("    tokenText: " + firstToken.get("tokenText"));
                    System.out.println("    tokenType: " + firstToken.get("tokenType"));
                    System.out.println("    scope: " + firstToken.get("scope"));
                    System.out.println("    qualifiedName: " + firstToken.get("qualifiedName"));
                }
            }
        }

        // Print a sample of the JSON (first 500 chars)
        System.out.println("\n  JSON sample (first 500 chars):");
        System.out.println("  " + json.substring(0, Math.min(500, json.length())));

        // === PROTOBUF DEFAULT VALUE OMISSION TEST ===
        System.out.println("\n=== PROTOBUF DEFAULT VALUE OMISSION TEST ===");

        // Test 1: registerAliasMap entry with value 0 (DR0)
        org.evochora.datapipeline.api.contracts.ProgramArtifact.Builder paBuilder =
                org.evochora.datapipeline.api.contracts.ProgramArtifact.newBuilder();
        paBuilder.putRegisterAliasMap("%TEST_DR0", 0);  // DR0 = 0 (default int32 value!)
        paBuilder.putRegisterAliasMap("%TEST_DR1", 1);  // DR1 = 1 (non-default)

        // Test 2: sourceMap entry with linearAddress=0
        paBuilder.addSourceMap(org.evochora.datapipeline.api.contracts.SourceMapEntry.newBuilder()
                .setLinearAddress(0)  // DEFAULT VALUE for int32!
                .setSourceInfo(org.evochora.datapipeline.api.contracts.SourceInfo.newBuilder()
                        .setFileName("test.evo").setLineNumber(1)));
        paBuilder.addSourceMap(org.evochora.datapipeline.api.contracts.SourceMapEntry.newBuilder()
                .setLinearAddress(5)  // non-default
                .setSourceInfo(org.evochora.datapipeline.api.contracts.SourceInfo.newBuilder()
                        .setFileName("test.evo").setLineNumber(10)));

        // Test 3: relativeCoordToLinearAddress with value 0
        paBuilder.putRelativeCoordToLinearAddress("0|0", 0);  // DEFAULT int32 value!
        paBuilder.putRelativeCoordToLinearAddress("1|0", 1);  // non-default

        String paJson = com.google.protobuf.util.JsonFormat.printer().print(paBuilder.build());
        com.fasterxml.jackson.databind.JsonNode paNode = mapper.readTree(paJson);

        // Check registerAliasMap
        com.fasterxml.jackson.databind.JsonNode regMap = paNode.get("registerAliasMap");
        System.out.println("  registerAliasMap present: " + (regMap != null));
        if (regMap != null) {
            System.out.println("    %TEST_DR0 (value=0): " + regMap.get("%TEST_DR0"));
            System.out.println("    %TEST_DR1 (value=1): " + regMap.get("%TEST_DR1"));
            System.out.println("    DR0 entry present: " + regMap.has("%TEST_DR0"));
        }

        // Check sourceMap
        com.fasterxml.jackson.databind.JsonNode srcMap = paNode.get("sourceMap");
        System.out.println("  sourceMap present: " + (srcMap != null));
        if (srcMap != null) {
            System.out.println("    sourceMap entries: " + srcMap.size());
            for (int i = 0; i < srcMap.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode entry = srcMap.get(i);
                System.out.println("    entry[" + i + "]: linearAddress=" + entry.get("linearAddress")
                        + " fileName=" + entry.path("sourceInfo").get("fileName"));
            }
        }

        // Check relativeCoordToLinearAddress
        com.fasterxml.jackson.databind.JsonNode coordMap = paNode.get("relativeCoordToLinearAddress");
        System.out.println("  relativeCoordToLinearAddress present: " + (coordMap != null));
        if (coordMap != null) {
            System.out.println("    '0|0' (value=0): " + coordMap.get("0|0"));
            System.out.println("    '1|0' (value=1): " + coordMap.get("1|0"));
            System.out.println("    '0|0' entry present: " + coordMap.has("0|0"));
        }

        // JavaScript-style check: would `entry.linearAddress === 0` work?
        System.out.println("\n  JS-simulation: sourceMap[0].linearAddress === 0?");
        if (srcMap != null && srcMap.size() > 0) {
            com.fasterxml.jackson.databind.JsonNode firstEntry = srcMap.get(0);
            com.fasterxml.jackson.databind.JsonNode laNode = firstEntry.get("linearAddress");
            System.out.println("    linearAddress field: " + laNode);
            System.out.println("    field is null (JS: undefined): " + (laNode == null));
            System.out.println("    VERDICT: Frontend lookup for address 0 would " +
                    (laNode == null ? "FAIL (field omitted by protobuf default value)" : "SUCCEED"));
        }

        // === FULL END-TO-END SERIALIZATION (replicating SimulationEngine → JSON) ===
        System.out.println("\n=== FULL END-TO-END SERIALIZATION ===");

        // Replicate SimulationEngine.convertProgramArtifact exactly
        org.evochora.datapipeline.api.contracts.ProgramArtifact.Builder fullBuilder =
                org.evochora.datapipeline.api.contracts.ProgramArtifact.newBuilder();
        fullBuilder.setProgramId(artifact.programId());

        artifact.sources().forEach((fn, srcLines) ->
                fullBuilder.putSources(fn, org.evochora.datapipeline.api.contracts.SourceLines.newBuilder()
                        .addAllLines(srcLines).build()));

        artifact.sourceMap().forEach((address, si) ->
                fullBuilder.addSourceMap(org.evochora.datapipeline.api.contracts.SourceMapEntry.newBuilder()
                        .setLinearAddress(address)
                        .setSourceInfo(org.evochora.datapipeline.api.contracts.SourceInfo.newBuilder()
                                .setFileName(si.fileName())
                                .setLineNumber(si.lineNumber())
                                .setColumnNumber(si.columnNumber()))));

        fullBuilder.putAllRelativeCoordToLinearAddress(artifact.relativeCoordToLinearAddress());
        fullBuilder.putAllRegisterAliasMap(artifact.registerAliasMap());
        fullBuilder.putAllLabelValueToName(artifact.labelValueToName());
        fullBuilder.putAllLabelNameToValue(artifact.labelNameToValue());

        artifact.tokenLookup().forEach((fn, lm) ->
                fullBuilder.addTokenLookup(org.evochora.datapipeline.api.contracts.FileTokenLookup.newBuilder()
                        .setFileName(fn)
                        .addAllLines(lm.entrySet().stream().map(le ->
                                org.evochora.datapipeline.api.contracts.LineTokenLookup.newBuilder()
                                        .setLineNumber(le.getKey())
                                        .addAllColumns(le.getValue().entrySet().stream().map(ce ->
                                                org.evochora.datapipeline.api.contracts.ColumnTokenLookup.newBuilder()
                                                        .setColumnNumber(ce.getKey())
                                                        .addAllTokens(ce.getValue().stream().map(ti ->
                                                                org.evochora.datapipeline.api.contracts.TokenInfo.newBuilder()
                                                                        .setTokenText(ti.tokenText())
                                                                        .setTokenType(ti.tokenType().name())
                                                                        .setScope(ti.scope())
                                                                        .build()).toList())
                                                        .build()).toList())
                                        .build()).toList())));

        // Serialize using the exact same printer as ProtobufConverter.toJson()
        String fullJson = com.google.protobuf.util.JsonFormat.printer().print(fullBuilder.build());
        com.fasterxml.jackson.databind.JsonNode fullNode = mapper.readTree(fullJson);

        // 1. Check programId
        System.out.println("  programId: " + fullNode.get("programId"));

        // 2. Check sources
        com.fasterxml.jackson.databind.JsonNode sourcesNode = fullNode.get("sources");
        System.out.println("  sources present: " + (sourcesNode != null));
        if (sourcesNode != null) {
            System.out.println("  sources keys: " + new ArrayList<String>() {{
                sourcesNode.fieldNames().forEachRemaining(this::add);
            }});
        }

        // 3. Check sourceMap (first 5 entries)
        com.fasterxml.jackson.databind.JsonNode fullSrcMap = fullNode.get("sourceMap");
        System.out.println("  sourceMap present: " + (fullSrcMap != null));
        System.out.println("  sourceMap size: " + (fullSrcMap != null ? fullSrcMap.size() : 0));
        if (fullSrcMap != null) {
            int limit = Math.min(5, fullSrcMap.size());
            for (int i = 0; i < limit; i++) {
                com.fasterxml.jackson.databind.JsonNode e = fullSrcMap.get(i);
                System.out.println("    [" + i + "]: linearAddress=" + e.get("linearAddress")
                        + " sourceInfo=" + e.get("sourceInfo"));
            }
        }

        // 4. Check relativeCoordToLinearAddress
        com.fasterxml.jackson.databind.JsonNode fullCoordMap = fullNode.get("relativeCoordToLinearAddress");
        System.out.println("  relativeCoordToLinearAddress present: " + (fullCoordMap != null));
        System.out.println("  relativeCoordToLinearAddress size: " + (fullCoordMap != null ? fullCoordMap.size() : 0));
        if (fullCoordMap != null) {
            System.out.println("    '0|0': " + fullCoordMap.get("0|0"));
            System.out.println("    '1|0': " + fullCoordMap.get("1|0"));
        }

        // 5. Check tokenLookup
        com.fasterxml.jackson.databind.JsonNode fullTokenLookup = fullNode.get("tokenLookup");
        System.out.println("  tokenLookup present: " + (fullTokenLookup != null));
        System.out.println("  tokenLookup size: " + (fullTokenLookup != null ? fullTokenLookup.size() : 0));
        if (fullTokenLookup != null && fullTokenLookup.size() > 0) {
            for (int i = 0; i < fullTokenLookup.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode fileEntry = fullTokenLookup.get(i);
                System.out.println("    file[" + i + "]: fileName=" + fileEntry.get("fileName")
                        + " linesCount=" + (fileEntry.get("lines") != null ? fileEntry.get("lines").size() : 0));
            }
        }

        // 6. Simulate frontend calculateActiveLocation for address 1
        System.out.println("\n=== FRONTEND SIMULATION: calculateActiveLocation ===");
        // Assume organism at relative [1, 0] (linearAddress=1)
        String testCoordKey = "1|0";
        com.fasterxml.jackson.databind.JsonNode testLa = fullCoordMap != null ? fullCoordMap.get(testCoordKey) : null;
        System.out.println("  coordKey '" + testCoordKey + "' -> linearAddress: " + testLa);

        if (testLa != null) {
            int testLinearAddress = testLa.intValue();
            // Find in sourceMap
            com.fasterxml.jackson.databind.JsonNode foundSourceInfo = null;
            if (fullSrcMap != null) {
                for (int i = 0; i < fullSrcMap.size(); i++) {
                    com.fasterxml.jackson.databind.JsonNode entry = fullSrcMap.get(i);
                    com.fasterxml.jackson.databind.JsonNode laField = entry.get("linearAddress");
                    if (laField != null && laField.intValue() == testLinearAddress) {
                        foundSourceInfo = entry;
                        break;
                    }
                }
            }
            System.out.println("  sourceMap.find(linearAddress=" + testLinearAddress + "): " +
                    (foundSourceInfo != null ? "FOUND" : "NOT FOUND"));
            if (foundSourceInfo != null) {
                com.fasterxml.jackson.databind.JsonNode si = foundSourceInfo.get("sourceInfo");
                if (si == null) si = foundSourceInfo;
                String foundFileName = si.get("fileName") != null ? si.get("fileName").asText() : null;
                int foundLineNumber = si.get("lineNumber") != null ? si.get("lineNumber").intValue() : -1;
                System.out.println("  fileName: " + foundFileName);
                System.out.println("  lineNumber: " + foundLineNumber);

                // Check if fileName matches first sources key
                String firstSourceKey = sourcesNode != null ? sourcesNode.fieldNames().next() : null;
                System.out.println("  selectedFile (first sources key): " + firstSourceKey);
                System.out.println("  fileName === selectedFile: " + (foundFileName != null && foundFileName.equals(firstSourceKey)));

                // 7. Simulate SourceAnnotator.annotate
                if (foundFileName != null && foundFileName.equals(firstSourceKey) && fullTokenLookup != null) {
                    System.out.println("\n=== FRONTEND SIMULATION: SourceAnnotator.annotate ===");
                    // Find tokenLookup entry for file
                    com.fasterxml.jackson.databind.JsonNode matchingFileEntry = null;
                    for (int i = 0; i < fullTokenLookup.size(); i++) {
                        com.fasterxml.jackson.databind.JsonNode fe = fullTokenLookup.get(i);
                        if (fe.get("fileName") != null && fe.get("fileName").asText().equals(foundFileName)) {
                            matchingFileEntry = fe;
                            break;
                        }
                    }
                    System.out.println("  tokenLookup file entry for '" + foundFileName + "': " +
                            (matchingFileEntry != null ? "FOUND" : "NOT FOUND"));

                    if (matchingFileEntry != null && matchingFileEntry.get("lines") != null) {
                        // Find line entry
                        com.fasterxml.jackson.databind.JsonNode matchingLine = null;
                        for (com.fasterxml.jackson.databind.JsonNode l : matchingFileEntry.get("lines")) {
                            if (l.get("lineNumber") != null && l.get("lineNumber").intValue() == foundLineNumber) {
                                matchingLine = l;
                                break;
                            }
                        }
                        System.out.println("  line entry for lineNumber=" + foundLineNumber + ": " +
                                (matchingLine != null ? "FOUND with " +
                                        (matchingLine.get("columns") != null ? matchingLine.get("columns").size() : 0)
                                        + " columns" : "NOT FOUND"));

                        if (matchingLine != null && matchingLine.get("columns") != null) {
                            for (com.fasterxml.jackson.databind.JsonNode col : matchingLine.get("columns")) {
                                int colNum = col.get("columnNumber") != null ? col.get("columnNumber").intValue() : -1;
                                com.fasterxml.jackson.databind.JsonNode toks = col.get("tokens");
                                System.out.println("    col=" + colNum + " tokens=" +
                                        (toks != null ? toks.size() : 0));
                                if (toks != null) {
                                    for (com.fasterxml.jackson.databind.JsonNode t : toks) {
                                        System.out.println("      text='" + t.get("tokenText") + "'"
                                                + " type=" + t.get("tokenType")
                                                + " scope=" + t.get("scope"));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 8. Summary assertions
        System.out.println("\n=== SUMMARY ===");
        assertNotNull(fullNode.get("programId"), "programId must be present");
        assertNotNull(sourcesNode, "sources must be present");
        assertTrue(sourcesNode.size() > 0, "sources must have entries");
        assertNotNull(fullSrcMap, "sourceMap must be present");
        assertTrue(fullSrcMap.size() > 0, "sourceMap must have entries");
        assertNotNull(fullCoordMap, "relativeCoordToLinearAddress must be present");
        assertNotNull(fullTokenLookup, "tokenLookup must be present");
        assertTrue(fullTokenLookup.size() > 0, "tokenLookup must have entries");
        System.out.println("  All structural assertions passed!");
    }
}
