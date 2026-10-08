package org.evochora.compiler.frontend.parser;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.features.instruction.InstructionParsingHandler;
import org.evochora.compiler.frontend.BlockKind;
import org.evochora.compiler.frontend.BlockReader;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for how the parser treats blocks, with two block kinds of its own and handlers
 * that build test nodes: a group with a body, and a selection with a head, named cases divided
 * by {@code .CASE} and an end that takes {@code EXPORT}. The selection handler uses exactly
 * what a block handler with dividers needs from the parsing context: the statements of a part,
 * the line of a divider and the token of the closer.
 */
@Tag("unit")
class ParserBlockTest {

    private static final BlockKind GROUP = new BlockKind(Set.of(".GROUP"), ".ENDGROUP", Set.of());
    private static final BlockKind SELECT = new BlockKind(Set.of(".SELECT"), ".ENDSELECT", Set.of(".CASE"));

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aWholeBlockIsParsedWithItsBodyAndTheParserContinuesAfterIt() {
        Parsed parsed = parse(
                ".GROUP G",
                "  NOP",
                ".ENDGROUP",
                "NOP");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.ast()).hasSize(2);
        Group group = (Group) parsed.ast().get(0);
        assertThat(group.name()).isEqualTo("G");
        assertThat(group.exported()).isFalse();
        assertThat(group.body()).singleElement().isInstanceOf(InstructionNode.class);
        assertThat(parsed.ast().get(1)).isInstanceOf(InstructionNode.class);
    }

    @Test
    void blocksNest() {
        Parsed parsed = parse(
                ".GROUP A",
                "  .GROUP B",
                "    NOP",
                "  .ENDGROUP",
                ".ENDGROUP");

        assertThat(parsed.errors()).isEmpty();
        Group outer = (Group) parsed.ast().getFirst();
        Group inner = (Group) outer.body().getFirst();
        assertThat(inner.name()).isEqualTo("B");
        assertThat(inner.body()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void dividersCutTheBlockIntoPartsWhoseHeadsTheHandlerReads() {
        Parsed parsed = parse(
                ".SELECT S",
                "  NOP",
                ".CASE X",
                "  NOP",
                "  NOP",
                "EXPORT .CASE Y",
                ".ENDSELECT");

        assertThat(parsed.errors()).isEmpty();
        Select select = (Select) parsed.ast().getFirst();
        assertThat(select.name()).isEqualTo("S");
        assertThat(select.head()).hasSize(1);
        assertThat(select.cases()).extracting(Case::name).containsExactly("X", "Y");
        assertThat(select.cases()).extracting(Case::exported).containsExactly(false, true);
        assertThat(select.cases().get(0).body()).hasSize(2);
        assertThat(select.cases().get(1).body()).isEmpty();
        assertThat(select.endExported()).isFalse();
        assertThat(select.endAt().lineNumber()).isEqualTo(7);
    }

    @Test
    void exportBeforeTheOpenerAndTheCloserBelongsToThoseWords() {
        Parsed parsed = parse(
                "EXPORT .SELECT S",
                "EXPORT .ENDSELECT");

        assertThat(parsed.errors()).isEmpty();
        Select select = (Select) parsed.ast().getFirst();
        assertThat(select.exported()).isTrue();
        assertThat(select.head()).isEmpty();
        assertThat(select.endExported()).isTrue();
        assertThat(select.endAt().lineNumber()).isEqualTo(2);
    }

    @Test
    void exportBeforeACloserThatDoesNotTakeItIsReportedAndTheBlockIsParsed() {
        Parsed parsed = parse(
                ".GROUP G",
                "EXPORT .ENDGROUP",
                "NOP");

        assertThat(parsed.errors()).containsExactly("<memory>:2: EXPORT is not supported before '.ENDGROUP'.");
        assertThat(parsed.ast()).hasSize(2);
        assertThat(parsed.ast().get(0)).isInstanceOf(Group.class);
        assertThat(parsed.ast().get(1)).isInstanceOf(InstructionNode.class);
    }

    @Test
    void aCloserOrDividerWithNoOpenBlockIsReportedWhereItStandsAndTheRestIsParsed() {
        Parsed parsed = parse(
                "NOP",
                ".ENDGROUP",
                ".CASE X",
                "NOP");

        assertThat(parsed.errors()).containsExactly(
                "<memory>:2: .ENDGROUP closes no open block",
                "<memory>:3: .CASE divides no open block");
        assertThat(parsed.ast()).hasSize(2).allMatch(InstructionNode.class::isInstance);
    }

    @Test
    void theCloserOfAnEnclosingBlockEndsTheInnerBlockAndTheBrokenBlockIsSkipped() {
        Parsed parsed = parse(
                ".GROUP G",
                "  .SELECT S",
                ".ENDGROUP",
                "NOP");

        assertThat(parsed.errors()).containsExactly(
                "<memory>:3: .ENDGROUP closes the .GROUP opened at <memory>:1, but the .SELECT opened at <memory>:2 is still open");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aBlockOpenAtTheEndOfTheInputIsReportedAtItsOpenerAndSkipped() {
        Parsed parsed = parse(
                "NOP",
                ".GROUP G",
                "  NOP");

        assertThat(parsed.errors()).containsExactly(
                "<memory>:2: .GROUP opened at <memory>:2 is not closed before the end of the input");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aHeaderErrorSkipsTheWholeBlockWithOneMessage() {
        Parsed parsed = parse(
                ".GROUP",
                "  NOP",
                ".ENDGROUP",
                "NOP");

        assertThat(parsed.errors()).containsExactly("<memory>:1: Expected a group name.");
        assertThat(parsed.ast()).singleElement().isInstanceOf(InstructionNode.class);
    }

    @Test
    void aBlockWordTakesNoStatementHandlerAndAKeywordBecomesNoBlockWord() {
        ParserStatementRegistry registry = new ParserStatementRegistry();
        registry.registerBlock(GROUP, new GroupHandler());
        registry.register(".OTHER", context -> null);

        assertThatThrownBy(() -> registry.register(".ENDGROUP", context -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".ENDGROUP");
        assertThatThrownBy(() -> registry.registerBlock(new BlockKind(Set.of(".OTHER"), ".ENDOTHER", Set.of()), new GroupHandler()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".OTHER");
        assertThatThrownBy(() -> registry.registerBlock(new BlockKind(Set.of(".LOOP"), ".ENDGROUP", Set.of()), new GroupHandler()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".ENDGROUP");
        assertThat(registry.blockHandlerOf(".group")).isPresent();
        assertThat(registry.blockHandlerOf(".ENDGROUP")).isEmpty();
        assertThat(registry.blockKindOf(".endgroup")).contains(GROUP);
    }

    // --- test nodes and handlers ---

    private record Group(String name, List<AstNode> body, boolean exported) implements AstNode {
        @Override
        public List<AstNode> getChildren() {
            return body;
        }
    }

    private record Case(String name, boolean exported, List<AstNode> body) {
    }

    private record Select(String name, boolean exported, List<AstNode> head, List<Case> cases, boolean endExported,
                          SourceInfo endAt) implements AstNode {
    }

    private static final class GroupHandler implements IParserBlockHandler {
        @Override
        public AstNode parse(IParsingContext context, BlockReader.Block block) {
            context.advance();
            Token name = context.consume(TokenType.IDENTIFIER, "Expected a group name.");
            boolean exported = context.isExported();
            context.consume(TokenType.NEWLINE, "Expected a newline after the group name.");
            List<AstNode> body = context.statements(block.bodyStart(), block.partEnd(block.closer()));
            return new Group(name.text(), body, exported);
        }

        @Override
        public boolean supportsExport(String word) {
            return ".GROUP".equalsIgnoreCase(word);
        }
    }

    private static final class SelectHandler implements IParserBlockHandler {
        @Override
        public AstNode parse(IParsingContext context, BlockReader.Block block) {
            context.advance();
            Token name = context.consume(TokenType.IDENTIFIER, "Expected a selection name.");
            boolean exported = context.isExported();
            List<Integer> words = new ArrayList<>(block.dividers());
            words.add(block.closer());
            List<AstNode> head = context.statements(block.bodyStart(), block.partEnd(words.getFirst()));
            List<Case> cases = new ArrayList<>();
            for (int k = 0; k < block.dividers().size(); k++) {
                int divider = block.dividers().get(k);
                DirectiveLine line = context.lineOf(divider);
                List<AstNode> body = context.statements(line.next(), block.partEnd(words.get(k + 1)));
                cases.add(new Case(line.operands().getFirst().text(), block.prefixed().contains(divider), body));
            }
            return new Select(name.text(), exported, head, cases, block.prefixed().contains(block.closer()),
                    context.tokenAt(block.closer()).source());
        }

        @Override
        public boolean supportsExport(String word) {
            return true;
        }
    }

    // --- helpers ---

    private record Parsed(List<AstNode> ast, DiagnosticsEngine diagnostics) {
        List<String> errors() {
            return diagnostics.getDiagnostics().stream()
                    .filter(d -> d.type() == Diagnostic.Type.ERROR)
                    .map(d -> d.fileName() + ":" + d.lineNumber() + ": " + d.message())
                    .toList();
        }
    }

    private static Parsed parse(String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = new Lexer(String.join("\n", lines) + "\n", diagnostics, TestLexers.symbols()).scanTokens();
        ParserStatementRegistry registry = new ParserStatementRegistry();
        registry.registerBlock(GROUP, new GroupHandler());
        registry.registerBlock(SELECT, new SelectHandler());
        registry.registerDefault(new InstructionParsingHandler());
        Parser parser = new Parser(tokens, diagnostics, registry);
        return new Parsed(parser.parse(), diagnostics);
    }
}
