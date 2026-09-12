package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefHolderTest {

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
    }

    @Test
    void setRetainsAndStoresTheNewReference() {
        // Given a fresh holder and a live reference
        RefHolder<TestObject> sut = new RefHolder<>();
        TestObject dict = new TestObject();

        // When set with a native call that succeeds
        sut.set(dict, _ -> { });

        // Then the reference is retained (constructor's own close alone does not free it)
        dict.close();
        assertThat(dict.tryCloseCount).hasValue(0);

        // And releasing the holder drops the held retain, freeing it
        sut.release();
        assertThat(dict.tryCloseCount).hasValue(1);
    }

    @Test
    void setPassesTheNewReferencesLivePointerToTheNativeCall() {
        // Given a holder and a live reference
        RefHolder<TestObject> sut = new RefHolder<>();
        TestObject dict = new TestObject();
        MemorySegment[] seen = new MemorySegment[1];

        // When set
        sut.set(dict, ptr -> seen[0] = ptr);

        // Then the native call received the dictionary's own pointer
        assertThat(seen[0]).isEqualTo(POINTER);
    }

    @Test
    void setWithNullPassesNullSegmentToTheNativeCall() {
        // Given a holder holding a reference
        RefHolder<TestObject> sut = new RefHolder<>();
        TestObject dict = new TestObject();
        sut.set(dict, _ -> { });
        MemorySegment[] seen = new MemorySegment[1];

        // When set to null
        sut.set(null, ptr -> seen[0] = ptr);

        // Then the native call received MemorySegment.NULL, not a dangling pointer
        assertThat(seen[0]).isEqualTo(MemorySegment.NULL);
    }

    @Test
    void setRollsBackTheRetainWhenTheNativeCallFails() {
        // Given a holder and a live reference
        RefHolder<TestObject> sut = new RefHolder<>();
        TestObject dict = new TestObject();

        // When set with a native call that fails
        Consumer<MemorySegment> failingCall = _ -> {
            throw new ZstdException("native call failed");
        };
        assertThatThrownBy(() -> sut.set(dict, failingCall)).isInstanceOf(ZstdException.class);

        // Then no reference was retained on the holder's behalf: the dictionary's
        // own constructor reference alone frees it
        dict.close();
        assertThat(dict.tryCloseCount).hasValue(1);
    }

    @Test
    void setReplacesAndReleasesThePreviouslyHeldReference() {
        // Given a holder already holding a first reference
        RefHolder<TestObject> sut = new RefHolder<>();
        TestObject first = new TestObject();
        TestObject second = new TestObject();
        sut.set(first, _ -> { });

        // When replaced by a second reference
        sut.set(second, _ -> { });

        // Then the first is released (its own close() alone now fully frees it)
        first.close();
        assertThat(first.tryCloseCount).hasValue(1);

        // And the second is still held
        second.close();
        assertThat(second.tryCloseCount).hasValue(0);
        sut.release();
        assertThat(second.tryCloseCount).hasValue(1);
    }

    @Test
    void setWithNullClearsTheHeldReference() {
        // Given a holder holding a reference
        RefHolder<TestObject> sut = new RefHolder<>();
        TestObject dict = new TestObject();
        sut.set(dict, _ -> { });

        // When set to null
        sut.set(null, _ -> { });

        // Then the held reference was released
        dict.close();
        assertThat(dict.tryCloseCount).hasValue(1);
    }

    @Test
    void releaseOnAnEmptyHolderIsANoOp() {
        // Given a holder that never held anything
        RefHolder<TestObject> sut = new RefHolder<>();

        // When releasing it repeatedly
        ThrowingCallable result = () -> {
            sut.release();
            sut.release();
        };

        // Then it does not throw
        assertThatCode(result).doesNotThrowAnyException();
    }
}
