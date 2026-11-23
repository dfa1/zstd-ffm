package io.github.dfa1.zstd;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class NativeCallTest {

    @Test
    void requireNativeAcceptsADirectByteBufferSegment() {
        // Given
        MemorySegment seg = MemorySegment.ofBuffer(ByteBuffer.allocateDirect(16));

        // When
        ThrowingCallable result = () -> NativeCall.requireNative(seg, "src");

        // Then
        assertThatCode(result).doesNotThrowAnyException();
    }

    @Test
    void requireNativeRejectsAHeapByteBufferSegment() {
        // Given
        MemorySegment seg = MemorySegment.ofBuffer(ByteBuffer.allocate(16));

        // When
        ThrowingCallable result = () -> NativeCall.requireNative(seg, "src");

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("src")
                .hasMessageContaining("native");
    }

    @Test
    void requireNativeRejectsNull() {
        // Given
        MemorySegment seg = null;

        // When
        ThrowingCallable result = () -> NativeCall.requireNative(seg, "src");

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("src");
    }

    @Test
    void requireReadableAcceptsAReadOnlyNativeSegment() {
        // Given
        MemorySegment seg = MemorySegment.ofBuffer(ByteBuffer.allocateDirect(16)).asReadOnly();

        // When
        ThrowingCallable result = () -> NativeCall.requireReadable(seg, "src");

        // Then
        assertThatCode(result).doesNotThrowAnyException();
    }

    @Test
    void requireReadableRejectsAHeapSegment() {
        // Given
        MemorySegment seg = MemorySegment.ofArray(new byte[16]);

        // When
        ThrowingCallable result = () -> NativeCall.requireReadable(seg, "src");

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("src must be a native");
    }

    @Test
    void requireWritableAcceptsAWritableNativeSegment() {
        // Given
        MemorySegment seg = MemorySegment.ofBuffer(ByteBuffer.allocateDirect(16));

        // When
        ThrowingCallable result = () -> NativeCall.requireWritable(seg, "dst");

        // Then
        assertThatCode(result).doesNotThrowAnyException();
    }

    @Test
    void requireWritableRejectsAReadOnlyNativeSegment() {
        // Given
        MemorySegment seg = MemorySegment.ofBuffer(ByteBuffer.allocateDirect(16)).asReadOnly();

        // When
        ThrowingCallable result = () -> NativeCall.requireWritable(seg, "dst");

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dst must be a writable MemorySegment; got a read-only segment");
    }

    @Test
    void requireWritableRejectsAHeapSegmentBeforeCheckingWritability() {
        // Given
        MemorySegment seg = MemorySegment.ofArray(new byte[16]).asReadOnly();

        // When
        ThrowingCallable result = () -> NativeCall.requireWritable(seg, "dst");

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("dst must be a native");
    }

    @Test
    void requireWritableRejectsNull() {
        // Given
        MemorySegment seg = null;

        // When
        ThrowingCallable result = () -> NativeCall.requireWritable(seg, "dst");

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dst");
    }
}
