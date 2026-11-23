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

        final AtomicInteger tryCloseCount;

        TestObject() {
            super(POINTER);
            this.tryCloseCount = new AtomicInteger();
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
    void borrowerKeepsPointerAliveAfterOwnerClosesButTheHandleIsUnusable() {
        // Given an object with a borrowed reference on top of the owner's
        TestObject sut = new TestObject();
        sut.retainForTest();

        // When the owner closes it
        sut.close();
        ThrowingCallable result = () -> sut.withRetained(MemorySegment::address);

        // Then the native pointer is not freed, but the closed handle rejects use
        assertThat(sut.tryCloseCount).hasValue(0);
        assertThatThrownBy(result)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void lastBorrowerReleaseFreesThePointerOfAClosedHandle() {
        // Given a closed handle whose pointer a borrower still holds
        TestObject sut = new TestObject();
        sut.retainForTest();
        sut.close();

        // When the borrower releases its reference
        sut.releaseForTest();

        // Then the native pointer is freed exactly once
        assertThat(sut.tryCloseCount).hasValue(1);
    }

    @Test
    void retainOnAClosedButStillBorrowedHandleThrowsWithoutTakingAReference() {
        // Given a closed handle still kept alive by one borrower
        TestObject sut = new TestObject();
        sut.retainForTest();
        sut.close();

        // When a new borrower tries to retain it
        ThrowingCallable result = sut::retainForTest;

        // Then it is rejected, and the existing borrower's release still frees the pointer
        assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        sut.releaseForTest();
        assertThat(sut.tryCloseCount).hasValue(1);
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

    @Test
    void withRetainedHandsTheCallTheLivePointer() {
        // Given a live handle
        try (TestObject sut = new TestObject()) {
            // When a call runs with the retained pointer
            long result = sut.withRetained(MemorySegment::address);

            // Then it received the handle's pointer
            assertThat(result).isEqualTo(POINTER.address());
        }
    }

    @Test
    void closeDuringACallDefersTheFreeUntilTheCallReturns() {
        // Given a handle closed while a call holds it (as another thread could)
        TestObject sut = new TestObject();
        AtomicInteger freesSeenDuringTheCall = new AtomicInteger(-1);

        // When the call closes the handle and then keeps using the pointer
        sut.withRetained(ptr -> {
            sut.close();
            freesSeenDuringTheCall.set(sut.tryCloseCount.get());
            return ptr.address();
        });

        // Then nothing was freed under the call, and the free happened on its return
        assertThat(freesSeenDuringTheCall).hasValue(0);
        assertThat(sut.tryCloseCount).hasValue(1);
    }

    @Test
    void withRetainedReleasesItsReferenceWhenTheCallThrows() {
        // Given a live handle
        TestObject sut = new TestObject();

        // When a call run with it fails
        ThrowingCallable result = () -> sut.withRetained(_ -> {
            throw new IllegalArgumentException("native failure");
        });

        // Then the failure propagates and leaves no reference behind: close alone frees it
        assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        sut.close();
        assertThat(sut.tryCloseCount).hasValue(1);
    }

    @Test
    void withRetainedOnAClosedHandleThrowsWithoutRunningTheCall() {
        // Given a closed handle
        TestObject sut = new TestObject();
        sut.close();
        AtomicInteger calls = new AtomicInteger();

        // When a call is attempted
        ThrowingCallable result = () -> sut.withRetained(_ -> calls.incrementAndGet());

        // Then it is refused before the call runs
        assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
        assertThat(sut.tryCloseCount).hasValue(1);
    }
}
