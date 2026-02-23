package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TickWorkerPoolTest {

    private TickWorkerPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void dispatchWritesAllElements() {
        pool = new TickWorkerPool(4);
        int[] data = new int[100];

        pool.dispatch(data.length, (from, to) -> {
            for (int i = from; i < to; i++) {
                data[i] = i * 2;
            }
        });

        for (int i = 0; i < data.length; i++) {
            assertThat(data[i]).isEqualTo(i * 2);
        }
    }

    @Test
    void dispatchWithSingleElement() {
        pool = new TickWorkerPool(4);
        int[] data = new int[1];

        pool.dispatch(1, (from, to) -> {
            for (int i = from; i < to; i++) {
                data[i] = 42;
            }
        });

        assertThat(data[0]).isEqualTo(42);
    }

    @Test
    void dispatchWithZeroSize() {
        pool = new TickWorkerPool(2);
        // Should return immediately without calling the task
        pool.dispatch(0, (from, to) -> {
            throw new AssertionError("Should not be called");
        });
    }

    @Test
    void workerExceptionPropagates() {
        pool = new TickWorkerPool(4);

        assertThatThrownBy(() ->
                pool.dispatch(100, (from, to) -> {
                    if (from > 0) {
                        throw new RuntimeException("Worker failure");
                    }
                })
        ).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Worker failure");
    }

    @Test
    void shutdownIsIdempotent() {
        pool = new TickWorkerPool(4);
        pool.shutdown();
        pool.shutdown();
        // No exception — idempotent
    }

    @Test
    void allThreadIndicesAssigned() {
        int parallelism = 4;
        pool = new TickWorkerPool(parallelism);
        Set<Integer> observedIndices = ConcurrentHashMap.newKeySet();

        pool.dispatch(parallelism * 10, (from, to) -> {
            observedIndices.add(TickWorkerPool.getThreadIndex());
        });

        assertThat(observedIndices).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    @Test
    void repeatedDispatchesDoNotDeadlock() {
        pool = new TickWorkerPool(4);
        AtomicIntegerArray counters = new AtomicIntegerArray(100);

        for (int round = 0; round < 1000; round++) {
            pool.dispatch(counters.length(), (from, to) -> {
                for (int i = from; i < to; i++) {
                    counters.incrementAndGet(i);
                }
            });
        }

        for (int i = 0; i < counters.length(); i++) {
            assertThat(counters.get(i)).isEqualTo(1000);
        }
    }

    @Test
    void dispatchWithTwoThreads() {
        pool = new TickWorkerPool(2);
        int[] data = new int[50];

        pool.dispatch(data.length, (from, to) -> {
            for (int i = from; i < to; i++) {
                data[i] = 1;
            }
        });

        for (int value : data) {
            assertThat(value).isEqualTo(1);
        }
    }

    @Test
    void constructorRejectsParallelismLessThanTwo() {
        assertThatThrownBy(() -> new TickWorkerPool(1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
