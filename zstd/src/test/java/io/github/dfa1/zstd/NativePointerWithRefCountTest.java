package io.github.dfa1.zstd;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativePointerWithRefCountTest {

    private static final MemorySegment POINTER = MemorySegment.ofAddress(0x1234);

    private static class TestObject extends NativePointerWithRefCount {

        final AtomicInteger tryCloseCount = new AtomicInteger();

        TestObject() {
            super(POINTER);
        }

        @Override
        protected void tryClose(MemorySegment ptr) {
            tryCloseCount.incrementAndGet();
        }

        void retainForTest() {
            retain();
        }

        void releaseForTest() {
            release();
        }
    }

    @Test
    void releaseSwallowsTryCloseFailure() {
        TestObject sut = new TestObject() {
            @Override
            protected void tryClose(MemorySegment ptr) {
                tryCloseCount.incrementAndGet();
                throw new RuntimeException("native free failed");
            }
        };

        sut.close(); // must not propagate

        assertThat(sut.tryCloseCount).hasValue(1);
    }

    @Test
    void closeAloneFreesOnce() {
        TestObject sut = new TestObject();
        sut.close();
        assertThat(sut.tryCloseCount).hasValue(1);
    }

    @Test
    void closeIsIdempotent() {
        TestObject sut = new TestObject();
        sut.close();
        sut.close();
        sut.close();
        assertThat(sut.tryCloseCount).hasValue(1);
    }

    @Test
    void borrowerKeepsPointerAliveAfterOwnerCloses() {
        TestObject sut = new TestObject();
        sut.retainForTest();

        sut.close(); // releases only the constructor's own reference
        assertThat(sut.tryCloseCount).hasValue(0);
        assertThat(sut.ptr()).isEqualTo(POINTER); // still usable through the borrowed reference

        sut.releaseForTest(); // last reference gone
        assertThat(sut.tryCloseCount).hasValue(1);
        assertThatThrownBy(sut::ptr).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retainAfterFullyReleasedThrowsAndDoesNotResurrect() {
        TestObject sut = new TestObject();
        sut.close();
        assertThat(sut.tryCloseCount).hasValue(1);

        assertThatThrownBy(sut::retainForTest).isInstanceOf(IllegalStateException.class);
        sut.releaseForTest(); // an errant unbalanced release must not free a second time
        assertThat(sut.tryCloseCount).hasValue(1);
    }

    @Test
    void concurrentRetainAndReleaseFreeExactlyOnce() throws InterruptedException {
        TestObject sut = new TestObject();
        int borrowers = 32;
        sut.retainForTest(); // hold one extra reference so the race is genuinely concurrent
        for (int i = 0; i < borrowers; i++) {
            sut.retainForTest();
        }

        CountDownLatch ready = new CountDownLatch(borrowers);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[borrowers];
        for (int i = 0; i < borrowers; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sut.releaseForTest();
            });
            threads[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }

        assertThat(sut.tryCloseCount).hasValue(0); // the extra hold + constructor's own reference still stand
        sut.releaseForTest(); // the extra hold
        sut.close(); // the constructor's own
        assertThat(sut.tryCloseCount).hasValue(1);
    }
}
