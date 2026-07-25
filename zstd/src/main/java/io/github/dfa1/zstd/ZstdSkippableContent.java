package io.github.dfa1.zstd;

import java.util.Arrays;

/// The decoded payload of a skippable frame, returned by
/// [ZstdFrame#readSkippableFrame(byte[])].
///
/// @param content      the user bytes carried by the skippable frame
/// @param magicVariant the variant the frame was written with
public record ZstdSkippableContent(byte[] content, ZstdMagicVariant magicVariant) {

    /// Defensively copies `content` so the record owns its bytes and cannot be
    /// mutated through the array the caller passed in.
    public ZstdSkippableContent {
        content = content.clone();
    }

    /// The embedded content bytes, as a fresh copy so the record stays immutable.
    ///
    /// @return a copy of the content bytes
    @Override
    public byte[] content() {
        return content.clone();
    }

    /// Value equality over the payload and variant, comparing `content` by its
    /// bytes rather than by array identity (the record default).
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdSkippableContent] with equal content bytes and variant
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdSkippableContent(byte[] otherContent, ZstdMagicVariant otherVariant)
                && magicVariant.equals(otherVariant)
                && Arrays.equals(content, otherContent);
    }

    /// Hash code consistent with [#equals(Object)], derived from the content bytes
    /// and the variant.
    ///
    /// @return the content-based hash code
    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(content) + magicVariant.hashCode();
    }

    /// Short description carrying the payload length and variant rather than the
    /// array's identity hash.
    ///
    /// @return a string with the content length and magic variant
    @Override
    @SuppressWarnings("NullableProblems") // toString never returns null; we just don't pull in JB @NotNull
    public String toString() {
        return "ZstdSkippableContent[content=" + content.length + " bytes, magicVariant=" + magicVariant.value() + "]";
    }
}
