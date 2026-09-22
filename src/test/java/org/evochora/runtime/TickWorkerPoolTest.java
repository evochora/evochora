package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class TickWorkerPoolTest {

    private TickWorkerPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void parallelWave_isActiveOnAllThreadsWhileDispatchingAndOffTheMainThreadAfterwards() {
        pool = new TickWorkerPool(2);
        boolean[] insideWave = new boolean[2];

        assertThat(ParallelWave.isActive()).isFalse();
        pool.dispatch(2, (from, to) -> {
            for (int i = from; i < to; i++) {
                insideWave[i] = ParallelWave.isActive();
            }
        });

        assertThat(insideWave).as("main thread chunk, worker chunk").containsOnly(true);
        assertThat(ParallelWave.isActive()).as("main thread has left the wave").isFalse();
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

    @Test
    void dispatchWithActiveThreadsSubset() {
        pool = new TickWorkerPool(8);
        int[] data = new int[100];

        // Use only 2 of 8 threads
        pool.dispatch(data.length, 2, (from, to) -> {
            for (int i = from; i < to; i++) {
                data[i] = 1;
            }
        });

        for (int value : data) {
            assertThat(value).isEqualTo(1);
        }
    }

    @Test
    void activeThreadsOnlyUsesRequestedThreads() {
        pool = new TickWorkerPool(8);
        Set<Integer> observedIndices = ConcurrentHashMap.newKeySet();

        pool.dispatch(100, 3, (from, to) -> {
            observedIndices.add(TickWorkerPool.getThreadIndex());
        });

        assertThat(observedIndices).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void activeThreadsClampedToPoolSize() {
        pool = new TickWorkerPool(4);
        int[] data = new int[100];

        // Request 16 threads, but pool only has 4
        pool.dispatch(data.length, 16, (from, to) -> {
            for (int i = from; i < to; i++) {
                data[i] = 1;
            }
        });

        for (int value : data) {
            assertThat(value).isEqualTo(1);
        }
    }

    @Test
    void dispatchWithSingleActiveThread() {
        pool = new TickWorkerPool(4);
        int[] data = new int[50];

        pool.dispatch(data.length, 1, (from, to) -> {
            for (int i = from; i < to; i++) {
                data[i] = 1;
            }
        });

        for (int value : data) {
            assertThat(value).isEqualTo(1);
        }
    }

    @Test
    void varyingActiveThreadsAcrossDispatches() {
        pool = new TickWorkerPool(8);
        AtomicIntegerArray counters = new AtomicIntegerArray(100);

        for (int round = 0; round < 500; round++) {
            int active = (round % 4) + 1; // cycles 1, 2, 3, 4
            pool.dispatch(counters.length(), active, (from, to) -> {
                for (int i = from; i < to; i++) {
                    counters.incrementAndGet(i);
                }
            });
        }

        for (int i = 0; i < counters.length(); i++) {
            assertThat(counters.get(i)).isEqualTo(500);
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void dispatchReturnsWhenTheSlowestWorkerFinishesLongAfterTheMainThread() {
        pool = new TickWorkerPool(4);
        int[] data = new int[400];

        pool.dispatch(data.length, (from, to) -> {
            if (TickWorkerPool.getThreadIndex() == 3) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (int i = from; i < to; i++) {
                data[i] = i;
            }
        });

        for (int i = 0; i < data.length; i++) {
            assertThat(data[i]).isEqualTo(i);
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void dispatchesWhoseWorkersFinishAroundTheMainThreadNeverHang() {
        pool = new TickWorkerPool(4);
        AtomicIntegerArray counters = new AtomicIntegerArray(64);

        // The burn per thread shifts from round to round, so that workers finish before, with and
        // after the main thread - the three orders in which the barrier hands over the wake-up.
        for (int round = 0; round < 2000; round++) {
            int spread = round % 7;
            pool.dispatch(counters.length(), (from, to) -> {
                int burn = TickWorkerPool.getThreadIndex() * spread * 200;
                for (int b = 0; b < burn; b++) {
                    Thread.onSpinWait();
                }
                for (int i = from; i < to; i++) {
                    counters.incrementAndGet(i);
                }
            });
        }

        for (int i = 0; i < counters.length(); i++) {
            assertThat(counters.get(i)).isEqualTo(2000);
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void dispatchWakesTheDispatchingThreadWhenAnotherThreadCreatedThePool() throws InterruptedException {
        // A service builds its simulation while it is being constructed and ticks it in its own
        // thread afterwards, so the thread that creates the pool never is the one that dispatches.
        pool = new TickWorkerPool(4);
        int[] data = new int[400];
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread dispatcher = new Thread(() -> {
            try {
                pool.dispatch(data.length, (from, to) -> {
                    if (TickWorkerPool.getThreadIndex() == 3) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    for (int i = from; i < to; i++) {
                        data[i] = i;
                    }
                });
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "dispatcher");

        dispatcher.start();
        dispatcher.join();

        assertThat(failure.get()).isNull();
        for (int i = 0; i < data.length; i++) {
            assertThat(data[i]).isEqualTo(i);
        }
    }
}
