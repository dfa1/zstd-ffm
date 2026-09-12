package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
                } catch (InterruptedException _) {
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

    @Test
    void staticReleaseDropsTheHeldReferenceAndReturnsNull() {
        // Given a "currently held" reference — its own reference plus the extra
        // hold, the way a swap() that stored it would have left it
        TestObject held = new TestObject();
        held.retainForTest();

        TestObject result = NativePointerWithRefCount.release(held);

        // Then only the hold's reference was dropped; the object's own remains
        assertThat(result).isNull();
        assertThat(held.tryCloseCount).hasValue(0);
        held.close();
        assertThat(held.tryCloseCount).hasValue(1);
    }

    @Test
    void staticReleaseOnNullIsANoOp() {
        assertThat(NativePointerWithRefCount.<TestObject>release(null)).isNull();
    }

    @Test
    void staticSwapRetainsNextAndReleasesHeldOnSuccess() {
        // Given a currently held reference and a new one to switch to
        TestObject held = new TestObject();
        held.retainForTest(); // the hold's own extra reference
        TestObject next = new TestObject();
        MemorySegment[] seenPtr = new MemorySegment[1];

        TestObject result = NativePointerWithRefCount.swap(held, next, ptr -> seenPtr[0] = ptr);

        // Then the native call saw next's live pointer, and the switch committed
        assertThat(result).isSameAs(next);
        assertThat(seenPtr[0]).isEqualTo(POINTER);

        // held's hold reference was released; only its own construction reference remains
        assertThat(held.tryCloseCount).hasValue(0);
        held.close();
        assertThat(held.tryCloseCount).hasValue(1);

        // next is now held: its own close() alone must not free it
        next.close();
        assertThat(next.tryCloseCount).hasValue(0);
    }

    @Test
    void staticSwapWithNullNextClearsAndReleasesHeld() {
        // Given a currently held reference and no replacement
        TestObject held = new TestObject();
        held.retainForTest();
        MemorySegment[] seenPtr = new MemorySegment[1];

        TestObject result = NativePointerWithRefCount.swap(held, null, ptr -> seenPtr[0] = ptr);

        // Then the native call saw MemorySegment.NULL and held's hold reference was released
        assertThat(result).isNull();
        assertThat(seenPtr[0]).isEqualTo(MemorySegment.NULL);
        assertThat(held.tryCloseCount).hasValue(0);
        held.close();
        assertThat(held.tryCloseCount).hasValue(1);
    }

    @Test
    void staticSwapRollsBackTheRetainOnNextWhenTheNativeCallFails() {
        // Given a currently held reference and a candidate replacement
        TestObject held = new TestObject();
        held.retainForTest();
        TestObject next = new TestObject();

        // When the native call fails
        ThrowingCallable result = () -> NativePointerWithRefCount.swap(held, next, ptr -> {
            throw new ZstdException("native call failed");
        });

        // Then the switch does not commit
        assertThatThrownBy(result).isInstanceOf(ZstdException.class);

        // held is untouched: its hold reference plus its own construction reference remain
        held.releaseForTest();
        assertThat(held.tryCloseCount).hasValue(0);
        held.close();
        assertThat(held.tryCloseCount).hasValue(1);

        // next's just-acquired retain was rolled back: its own close() alone frees it
        next.close();
        assertThat(next.tryCloseCount).hasValue(1);
    }
}
