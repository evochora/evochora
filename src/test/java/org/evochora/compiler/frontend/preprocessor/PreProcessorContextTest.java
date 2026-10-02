package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the feature state slot and the options of {@link PreProcessorContext}.
 */
@Tag("unit")
class PreProcessorContextTest {

    private static final class FirstState {
        final List<String> seen = new ArrayList<>();
    }

    private static final class SecondState {
        int count;
    }

    @Test
    void getOrCreate_returnsTheSameInstanceAndCreatesOnce() {
        PreProcessorContext context = new PreProcessorContext();
        AtomicInteger created = new AtomicInteger();

        FirstState first = context.getOrCreate(FirstState.class, () -> {
            created.incrementAndGet();
            return new FirstState();
        });
        FirstState again = context.getOrCreate(FirstState.class, () -> {
            created.incrementAndGet();
            return new FirstState();
        });

        assertThat(again).isSameAs(first);
        assertThat(created).hasValue(1);
        assertThat(context.get(FirstState.class)).isSameAs(first);
    }

    @Test
    void getOrCreate_keepsDifferentKeysApart() {
        PreProcessorContext context = new PreProcessorContext();

        FirstState first = context.getOrCreate(FirstState.class, FirstState::new);
        SecondState second = context.getOrCreate(SecondState.class, SecondState::new);
        first.seen.add("x");
        second.count = 3;

        assertThat(context.getOrCreate(FirstState.class, FirstState::new).seen).containsExactly("x");
        assertThat(context.getOrCreate(SecondState.class, SecondState::new).count).isEqualTo(3);
    }

    @Test
    void get_withoutState_returnsNull() {
        assertThat(new PreProcessorContext().get(FirstState.class)).isNull();
    }

    @Test
    void noArgConstructor_hasDefaultOptions() {
        assertThat(new PreProcessorContext().options()).isEqualTo(CompilerOptions.defaults());
    }

    @Test
    void options_areThoseGiven() {
        CompilerOptions options = new CompilerOptions(
                List.of(new SourceRoot(".", null)), Map.of("FLAG", OptionalInt.of(2)));
        assertThat(new PreProcessorContext("", Map.of(), options).options()).isSameAs(options);
    }
}
