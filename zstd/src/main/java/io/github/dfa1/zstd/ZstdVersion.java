package io.github.dfa1.zstd;

/// The semantic version of the linked libzstd, replacing the split
/// `String`/`int` version accessors with one comparable value.
///
/// zstd encodes its version two ways — a `"MAJOR.MINOR.PATCH"` string and a
/// packed `MAJOR * 10000 + MINOR * 100 + PATCH` number. This type carries both:
/// the components directly, [#number()] for the packed form, and [#toString()]
/// for the string. Being [Comparable], it lets callers feature-gate against the
/// runtime library, e.g. `Zstd.version().compareTo(new ZstdVersion(1, 5, 0)) >= 0`.
///
/// @param major the major version component
/// @param minor the minor version component
/// @param patch the patch version component
public record ZstdVersion(int major, int minor, int patch) implements Comparable<ZstdVersion> {

    /// Validates the components are non-negative.
    public ZstdVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version " + major + "." + minor + "." + patch + " must not be negative");
        }
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
}
