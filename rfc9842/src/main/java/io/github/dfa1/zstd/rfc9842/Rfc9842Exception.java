package io.github.dfa1.zstd.rfc9842;

import java.io.Serial;

/// Thrown when `dcz`-framed data does not match the RFC 9842 wire format, or
/// its embedded hash does not match the dictionary it is checked against.
///
/// Unchecked: a mismatch here means the wrong dictionary is in hand, or the
/// data is corrupt or not a `dcz` frame at all — not a recoverable I/O condition.
public final class Rfc9842Exception extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    Rfc9842Exception(String message) {
        super(message);
    }

    Rfc9842Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
