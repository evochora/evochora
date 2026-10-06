package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.TokenKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenLookup}: the token map nested by placement, file, line and column.
 */
@Tag("unit")
class TokenLookupTest {

    @Test
    void nestsByPlacementThenFileThenLineThenColumn() {
        TokenInfo first = new TokenInfo("A", TokenKind.CONSTANT, TokenInfo.MODULE_LEVEL);
        TokenInfo second = new TokenInfo("B", TokenKind.CONSTANT, TokenInfo.MODULE_LEVEL);
        TokenInfo other = new TokenInfo("A", TokenKind.CONSTANT, TokenInfo.MODULE_LEVEL);
        Map<SourceInfo, TokenInfo> tokenMap = new LinkedHashMap<>();
        tokenMap.put(new SourceInfo("lib.evo", 2, 5, "FIRST", 0), first);
        tokenMap.put(new SourceInfo("lib.evo", 2, 9, "FIRST", 0), second);
        tokenMap.put(new SourceInfo("lib.evo", 2, 5, "SECOND", 0), other);

        Map<String, Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>>> lookup = TokenLookup.of(tokenMap);

        assertThat(lookup).containsOnlyKeys("FIRST", "SECOND");
        assertThat(lookup.get("FIRST")).containsOnlyKeys("lib.evo");
        assertThat(lookup.get("FIRST").get("lib.evo")).containsOnlyKeys(2);
        assertThat(lookup.get("FIRST").get("lib.evo").get(2)).containsOnlyKeys(5, 9);
        assertThat(lookup.get("FIRST").get("lib.evo").get(2).get(5)).containsExactly(first);
        assertThat(lookup.get("FIRST").get("lib.evo").get(2).get(9)).containsExactly(second);
        assertThat(lookup.get("SECOND").get("lib.evo").get(2).get(5)).containsExactly(other);
    }

    @Test
    void anEmptyMapGivesAnEmptyLookup() {
        assertThat(TokenLookup.of(Map.of())).isEmpty();
    }
}
