package org.evochora.compiler.features.constdir;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.backend.emit.EmissionContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The artifact names the value of every constant under the qualified name the token map gives a
 * use of it, so that the source view can show the value where the constant is used.
 */
class ConstantValuesTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    @Tag("unit")
    void theContributorRegistersTheValueOfAConstValueDirective_andIgnoresOtherDirectives() {
        EmissionContext context = new EmissionContext();
        ConstantValueEmissionContributor contributor = new ConstantValueEmissionContributor();
        SourceInfo at = new SourceInfo("main.evo", 1, 1, "", 0);

        contributor.onItem(new IrDirective("constdir", "const_value",
                Map.of("name", new IrValue.Str("LIB.MAX"), "value", new IrValue.Str("DATA:5")), at), context);
        contributor.onItem(new IrDirective("reg", "reg_alias",
                Map.of("name", new IrValue.Str("TMP"), "register", new IrValue.Str("%DR0")), at), context);

        assertThat(context.constantValues()).containsExactly(Map.entry("LIB.MAX", "DATA:5"));
    }

    /**
     * Every value form is written as the definition gave it, numbers in decimal, and the key is
     * the qualified name the token map gives the constant's use.
     */
    @Test
    @Tag("integration")
    void everyConstantIsListedUnderTheQualifiedNameOfItsUse() throws Exception {
        write("main.evo",
                ".CONST LIMIT DATA:9",
                ".CONST STEP 1|0",
                ".CONST COUNT 0x10",
                ".CONST ALSO LIMIT",
                "START:",
                "  SETI %DR0 LIMIT",
                "  SETV %DR1 STEP",
                "  SETI %DR2 ALSO");

        ProgramArtifact artifact = compile();

        assertThat(artifact.constantValues()).containsOnly(
                Map.entry("LIMIT", "DATA:9"), Map.entry("STEP", "1|0"), Map.entry("COUNT", "16"),
                Map.entry("ALSO", "LIMIT"));
        TokenInfo use = artifact.tokenMap().get(new SourceInfo(resolved("main.evo"), 6, 13, "", 0));
        assertThat(artifact.constantValues()).containsKey(use.qualifiedName());
    }

    /**
     * A module placed twice under other flags defines its constant twice with other values; each
     * placement's value stands under the placement's qualified name.
     */
    @Test
    @Tag("integration")
    void aConstantOfAModulePlacedTwiceHasOneValuePerPlacement() throws Exception {
        write("lib.evo",
                ".IFDEF BIG",
                ".CONST X DATA:9",
                ".ELSEDEF",
                ".CONST X DATA:1",
                ".ENDDEF",
                "EXPORT .PROC WORK",
                "  SETI %DR0 X",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".DEFINE BIG",
                ".IMPORT \"lib.evo\" AS FIRST",
                ".UNDEF BIG",
                ".IMPORT \"lib.evo\" AS SECOND",
                "START:",
                "  CALL FIRST.WORK",
                "  CALL SECOND.WORK");

        ProgramArtifact artifact = compile();

        assertThat(artifact.constantValues()).containsOnly(
                Map.entry("FIRST.X", "DATA:9"), Map.entry("SECOND.X", "DATA:1"));
        TokenInfo first = artifact.tokenMap().get(new SourceInfo(resolved("lib.evo"), 7, 13, "FIRST", 0));
        TokenInfo second = artifact.tokenMap().get(new SourceInfo(resolved("lib.evo"), 7, 13, "SECOND", 0));
        assertThat(first.qualifiedName()).isEqualTo("FIRST.X");
        assertThat(second.qualifiedName()).isEqualTo("SECOND.X");
    }

    /**
     * A constant and a register alias defined under one name in two procedures are two
     * definitions: each is filed under its own path, and the token map names, for each use, the
     * path of its own definition and the procedure as its scope.
     */
    @Test
    @Tag("integration")
    void aNameDefinedInTwoProceduresHasOneKeyPerProcedure() throws Exception {
        write("main.evo",
                "START:",
                "  CALL FIRST",
                "  CALL SECOND",
                ".PROC FIRST",
                "  .CONST N DATA:1",
                "  .REG %TMP %DR0",
                "  SETI %TMP N",
                "  RET",
                ".ENDPROC",
                ".PROC SECOND",
                "  .CONST N DATA:2",
                "  .REG %TMP %DR1",
                "  SETI %TMP N",
                "  RET",
                ".ENDPROC");

        ProgramArtifact artifact = compile();

        assertThat(artifact.constantValues()).containsOnly(Map.entry("FIRST.N", "DATA:1"), Map.entry("SECOND.N", "DATA:2"));
        assertThat(artifact.registerAliasMap()).containsOnlyKeys("FIRST.%TMP", "SECOND.%TMP");
        assertThat(artifact.registerAliasMap().get("FIRST.%TMP")).isNotEqualTo(artifact.registerAliasMap().get("SECOND.%TMP"));
        String main = resolved("main.evo");
        for (int line : new int[]{7, 13}) {
            TokenInfo constant = artifact.tokenMap().get(new SourceInfo(main, line, 13, "", 0));
            TokenInfo alias = artifact.tokenMap().get(new SourceInfo(main, line, 8, "", 0));
            String procedure = line == 7 ? "FIRST" : "SECOND";
            assertThat(constant.scope()).isEqualTo(procedure);
            assertThat(alias.scope()).isEqualTo(procedure);
            assertThat(artifact.constantValues().get(constant.qualifiedName()))
                    .isEqualTo(line == 7 ? "DATA:1" : "DATA:2");
            assertThat(artifact.registerAliasMap()).containsKey(alias.qualifiedName());
        }
    }

    private String resolved(String fileName) {
        return sourceRoot.resolve(fileName).toString().replace('\\', '/');
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile() throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        return new Compiler().compile("main.evo", ENV, options);
    }
}
