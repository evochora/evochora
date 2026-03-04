package org.evochora.compiler.frontend.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ParserState} scope broadcasting and auto-registration.
 */
@Tag("unit")
class ParserStateScopeTest {

    private ParserState state;

    @BeforeEach
    void setUp() {
        state = new ParserState();
    }

    @Test
    void broadcastsPushToAllRegisteredStates() {
        TrackingScopedState a = state.getOrCreate(TrackingScopedState.class, TrackingScopedState::new);
        TrackingScopedState2 b = state.getOrCreate(TrackingScopedState2.class, TrackingScopedState2::new);

        state.pushScope();

        assertThat(a.events).containsExactly("push");
        assertThat(b.events).containsExactly("push");
    }

    @Test
    void broadcastsPopToAllRegisteredStates() {
        TrackingScopedState a = state.getOrCreate(TrackingScopedState.class, TrackingScopedState::new);
        TrackingScopedState2 b = state.getOrCreate(TrackingScopedState2.class, TrackingScopedState2::new);

        state.pushScope();
        state.popScope();

        assertThat(a.events).containsExactly("push", "pop");
        assertThat(b.events).containsExactly("push", "pop");
    }

    @Test
    void autoRegistersOnlyViaGetOrCreate() {
        TrackingScopedState scoped = new TrackingScopedState();
        state.put(TrackingScopedState.class, scoped);

        state.pushScope();

        assertThat(scoped.events).isEmpty();
    }

    @Test
    void nestedScopesBroadcastCorrectly() {
        TrackingScopedState a = state.getOrCreate(TrackingScopedState.class, TrackingScopedState::new);

        state.pushScope();
        state.pushScope();
        state.popScope();
        state.popScope();

        assertThat(a.events).containsExactly("push", "push", "pop", "pop");
    }

    @Test
    void noOpWhenNoScopedStatesRegistered() {
        state.pushScope();
        state.popScope();
        // No exception — broadcasting to empty list is a no-op
    }

    @Test
    void doesNotDoubleRegisterOnRepeatedGetOrCreate() {
        state.getOrCreate(TrackingScopedState.class, TrackingScopedState::new);
        state.getOrCreate(TrackingScopedState.class, TrackingScopedState::new);

        TrackingScopedState a = state.get(TrackingScopedState.class);
        state.pushScope();

        assertThat(a.events).containsExactly("push");
    }

    private static class TrackingScopedState implements IScopedParserState {
        final List<String> events = new ArrayList<>();

        @Override
        public void pushScope() { events.add("push"); }

        @Override
        public void popScope() { events.add("pop"); }
    }

    private static class TrackingScopedState2 implements IScopedParserState {
        final List<String> events = new ArrayList<>();

        @Override
        public void pushScope() { events.add("push"); }

        @Override
        public void popScope() { events.add("pop"); }
    }
}
