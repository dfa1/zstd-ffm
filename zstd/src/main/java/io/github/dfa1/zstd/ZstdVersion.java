package io.github.dfa1.zstd;

import java.util.Objects;

/// The semantic version of the linked libzstd, replacing the split
/// `String`/`int` version accessors with one comparable value.
///
/// zstd encodes its version two ways — a `"MAJOR.MINOR.PATCH"` string and a
/// packed `MAJOR * 10000 + MINOR * 100 + PATCH` number. This type carries both:
/// the components directly, [#number()] for the packed form, and [#toString()]
/// for the string. Being [Comparable], it lets callers feature-gate against the
/// runtime library, e.g. `Zstd.version().compareTo(ZstdVersion.of(1, 5, 0)) >= 0`.
public final class ZstdVersion implements Comparable<ZstdVersion> {

    private final int major;
    private final int minor;
    private final int patch;

    private ZstdVersion(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version " + major + "." + minor + "." + patch + " must not be negative");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /// Creates a [ZstdVersion] from its components, validating them.
    ///
    /// @param major the major version component
    /// @param minor the minor version component
    /// @param patch the patch version component
    /// @return the new [ZstdVersion]
    /// @throws IllegalArgumentException if a component is negative
    public static ZstdVersion of(int major, int minor, int patch) {
        return new ZstdVersion(major, minor, patch);
    }

    /// Decodes zstd's packed `MAJOR * 10000 + MINOR * 100 + PATCH` version number.
    ///
    /// @param number the packed version number, as `ZSTD_versionNumber` returns it
    /// @return the decoded version
    static ZstdVersion ofNumber(int number) {
        return new ZstdVersion(number / 10000, (number / 100) % 100, number % 100);
    }

    /// The packed version number, `major * 10000 + minor * 100 + patch` — zstd's
    /// own `ZSTD_versionNumber` encoding.
    ///
    /// @return the packed version number
    public int number() {
        return major * 10000 + minor * 100 + patch;
    }

    /// Orders by `major`, then `minor`, then `patch`.
    ///
    /// @param other the version to compare with
    /// @return a negative, zero, or positive value as this version is less than,
    ///         equal to, or greater than `other`
    @Override
    public int compareTo(ZstdVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        if (byMinor != 0) {
            return byMinor;
        }
        return Integer.compare(patch, other.patch);
    }

    /// The `"MAJOR.MINOR.PATCH"` string, matching `ZSTD_versionString`.
    ///
    /// @return the version as an `x.y.z` string
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    /// The major version component.
    ///
    /// @return the major version component
    public int major() {
        return major;
    }

    /// The minor version component.
    ///
    /// @return the minor version component
    public int minor() {
        return minor;
    }

    /// The patch version component.
    ///
    /// @return the patch version component
    public int patch() {
        return patch;
    }

    /// Value equality over all components, as a record would compare them.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdVersion] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdVersion other
                && major == other.major
                && minor == other.minor
                && patch == other.patch;
    }

    /// Hash code consistent with [#equals(Object)].
    ///
    /// @return the component-based hash code
    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }
}
