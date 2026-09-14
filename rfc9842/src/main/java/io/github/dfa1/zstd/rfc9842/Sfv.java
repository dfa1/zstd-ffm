package io.github.dfa1.zstd.rfc9842;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/// Minimal RFC 8941 (Structured Field Values) parser/serializer: only the
/// subset RFC 9842's three headers actually use — bare String, Token, and
/// Byte Sequence items, an Inner List of Strings, and the member-key
/// grammar for a Dictionary's keys. Not a general-purpose SFV library:
/// Integers, Decimals, Booleans, and per-member Parameters are not
/// implemented, since none of RFC 9842's headers use them.
final class Sfv {

    private Sfv() {
        // no instances
    }

    /// A read position within a structured-field-value string being parsed.
    static final class Cursor {

        private final String s;
        private int pos;

        Cursor(String s) {
            this.s = s;
        }

        boolean isEmpty() {
            return pos >= s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        char consume() {
            return s.charAt(pos++);
        }

        /// Discards optional whitespace (space or tab), per OWS.
        void discardOws() {
            while (!isEmpty() && (peek() == ' ' || peek() == '\t')) {
                pos++;
            }
        }

        /// Discards space characters only, per the inner-list grammar's `*SP`.
        void discardSp() {
            while (!isEmpty() && peek() == ' ') {
                pos++;
            }
        }

        void expect(char c, String what) {
            if (isEmpty() || consume() != c) {
                throw new Rfc9842Exception(
                        "malformed structured field value: expected '" + c + "' (" + what + ")");
            }
        }
    }

    static Cursor cursor(String input) {
        return new Cursor(input);
    }

    // ---- Key (member-key / param-key): lcalpha/"*" then lcalpha/DIGIT/"_"/"-"/"."/"*" ----

    static String parseKey(Cursor c) {
        if (c.isEmpty() || !isLcalphaOrStar(c.peek())) {
            throw new Rfc9842Exception("malformed structured field value: key must start with a lowercase letter or '*'");
        }
        StringBuilder out = new StringBuilder();
        out.append(c.consume());
        while (!c.isEmpty() && isKeyChar(c.peek())) {
            out.append(c.consume());
        }
        return out.toString();
    }

    private static boolean isLcalphaOrStar(char ch) {
        return (ch >= 'a' && ch <= 'z') || ch == '*';
    }

    private static boolean isKeyChar(char ch) {
        return isLcalphaOrStar(ch) || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.';
    }

    // ---- String ----

    static String parseString(Cursor c) {
        c.expect('"', "string open quote");
        StringBuilder out = new StringBuilder();
        while (true) {
            if (c.isEmpty()) {
                throw new Rfc9842Exception("malformed structured field value: unterminated string");
            }
            char ch = c.consume();
            if (ch == '\\') {
                if (c.isEmpty()) {
                    throw new Rfc9842Exception("malformed structured field value: dangling escape in string");
                }
                char next = c.consume();
                if (next != '"' && next != '\\') {
                    throw new Rfc9842Exception("malformed structured field value: invalid escape '\\" + next + "' in string");
                }
                out.append(next);
            } else if (ch == '"') {
                return out.toString();
            } else if (ch < 0x20 || ch == 0x7f) {
                throw new Rfc9842Exception("malformed structured field value: control character in string");
            } else {
                out.append(ch);
            }
        }
    }

    static String serializeString(String value) {
        StringBuilder out = new StringBuilder();
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch > 0x7e || (ch < 0x20 && ch != ' ') || ch == 0x7f) {
                throw new Rfc9842Exception(
                        "cannot serialize as a structured field string: non-ASCII/control character");
            }
            if (ch == '\\' || ch == '"') {
                out.append('\\');
            }
            out.append(ch);
        }
        return out.append('"').toString();
    }

    // ---- Token ----

    private static boolean isTchar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(ch) >= 0;
    }

    private static boolean startsToken(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '*';
    }

    static String parseToken(Cursor c) {
        if (c.isEmpty() || !startsToken(c.peek())) {
            throw new Rfc9842Exception("malformed structured field value: token must start with a letter or '*'");
        }
        StringBuilder out = new StringBuilder();
        out.append(c.consume());
        while (!c.isEmpty() && (isTchar(c.peek()) || c.peek() == ':' || c.peek() == '/')) {
            out.append(c.consume());
        }
        return out.toString();
    }

    static String serializeToken(String value) {
        if (value.isEmpty() || !startsToken(value.charAt(0))) {
            throw new Rfc9842Exception("cannot serialize as a structured field token: must start with a letter or '*'");
        }
        for (int i = 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!(isTchar(ch) || ch == ':' || ch == '/')) {
                throw new Rfc9842Exception("cannot serialize as a structured field token: invalid character '" + ch + "'");
            }
        }
        return value;
    }

    // ---- Byte Sequence ----

    static byte[] parseByteSequence(Cursor c) {
        c.expect(':', "byte sequence open colon");
        StringBuilder b64 = new StringBuilder();
        while (true) {
            if (c.isEmpty()) {
                throw new Rfc9842Exception("malformed structured field value: unterminated byte sequence");
            }
            char ch = c.consume();
            if (ch == ':') {
                break;
            }
            b64.append(ch);
        }
        try {
            return Base64.getDecoder().decode(b64.toString());
        } catch (IllegalArgumentException e) {
            throw new Rfc9842Exception("malformed structured field value: invalid base64 in byte sequence", e);
        }
    }

    static String serializeByteSequence(byte[] value) {
        return ":" + Base64.getEncoder().encodeToString(value) + ":";
    }

    // ---- Inner List of Strings ----

    static List<String> parseInnerListOfStrings(Cursor c) {
        c.expect('(', "inner list open paren");
        List<String> items = new ArrayList<>();
        c.discardSp();
        if (!c.isEmpty() && c.peek() != ')') {
            items.add(parseString(c));
            c.discardSp();
            while (!c.isEmpty() && c.peek() != ')') {
                items.add(parseString(c));
                c.discardSp();
            }
        }
        c.expect(')', "inner list close paren");
        return items;
    }
}
