package io.github.dfa1.zstd;

import java.util.Arrays;

/// The decoded payload of a skippable frame, returned by
/// [ZstdFrame#readSkippableFrame(byte[])].
///
/// A plain class, not a record: a record's canonical constructor cannot be
/// narrower than the record itself, so a `public record` can never restrict
/// construction to a factory method the way [#content] is restricted here —
/// [ZstdFrame#readSkippableFrame(byte[])] is the only public way to get an
/// instance, so every one actually came from a real skippable frame rather
/// than a caller fabricating one. A record's forced-public accessor for a
/// `byte[]` component is also easy to leave un-overridden, silently handing
/// out the live array; a hand-written class has no such trap.
public final class ZstdSkippableContent {

    private final byte[] content;
    private final ZstdMagicVariant magicVariant;

    /// Defensively copies `content` so this instance owns its bytes and
    /// cannot be mutated through the array the caller passed in.
    ///
    /// @param content      the user bytes carried by the skippable frame
    /// @param magicVariant the variant the frame was written with
    ZstdSkippableContent(byte[] content, ZstdMagicVariant magicVariant) {
        this.content = content.clone();
        this.magicVariant = magicVariant;
    }

    /// The embedded content bytes, as a fresh copy so this instance stays immutable.
    ///
    /// @return a copy of the content bytes
    public byte[] content() {
        return content.clone();
    }

    /// The variant the frame was written with.
    ///
    /// @return the magic variant
    public ZstdMagicVariant magicVariant() {
        return magicVariant;
    }

    /// Value equality over the payload and variant, comparing the content by
    /// its bytes rather than by array identity.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdSkippableContent] with equal content bytes and variant
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdSkippableContent other
                && magicVariant.equals(other.magicVariant)
                && Arrays.equals(content, other.content);
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
    /// array field's identity hash.
    ///
    /// @return a string with the content length and magic variant
    @Override
    public String toString() {
        return "ZstdSkippableContent[content=" + content.length + " bytes, magicVariant=" + magicVariant.value() + "]";
    }
}
