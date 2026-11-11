#!/usr/bin/env python3
"""Compile formatter patterns with native ICU, separately from the JVM test engine.

Android's java.util.regex uses ICU; desktop JVM and Robolectric regex tests do not.
The v5 closing-brace error passed those tests but fails this check. Requires the
system ICU shared libraries (present in the Linux development/CI environment).
No dependency is added to the Android plugin.
"""
import ctypes as c
import ctypes.util
import json
from pathlib import Path
import re


class ParseError(c.Structure):
    _fields_ = [("line", c.c_int32), ("offset", c.c_int32),
                ("pre", c.c_uint16 * 16), ("post", c.c_uint16 * 16)]


def symbol(library, name):
    versions = re.findall(r"\d+", library._name)
    for suffix in [""] + ["_" + v for v in reversed(versions)]:
        try:
            return getattr(library, name + suffix)
        except AttributeError:
            pass
    raise RuntimeError("Missing ICU function: " + name)


def uchar(text):
    data = text.encode("utf-16-le")
    return (c.c_uint16 * (len(data) // 2)).from_buffer_copy(data)


class ICU:
    def __init__(self):
        name = ctypes.util.find_library("icui18n")
        common = ctypes.util.find_library("icuuc")
        if not name or not common:
            raise RuntimeError("Native ICU libraries are required; this check must not be skipped.")
        self.name = name
        self.lib, self.common = c.CDLL(name), c.CDLL(common)
        self.open = symbol(self.lib, "uregex_open")
        self.open.argtypes = [c.POINTER(c.c_uint16), c.c_int32, c.c_uint32, c.POINTER(ParseError), c.POINTER(c.c_int32)]
        self.open.restype = c.c_void_p
        self.close = symbol(self.lib, "uregex_close")
        self.close.argtypes = [c.c_void_p]
        self.set_text = symbol(self.lib, "uregex_setText")
        self.set_text.argtypes = [c.c_void_p, c.POINTER(c.c_uint16), c.c_int32, c.POINTER(c.c_int32)]
        self.find = symbol(self.lib, "uregex_findNext")
        self.find.argtypes = [c.c_void_p, c.POINTER(c.c_int32)]
        self.find.restype = c.c_int8
        self.start = symbol(self.lib, "uregex_start")
        self.start.argtypes = [c.c_void_p, c.c_int32, c.POINTER(c.c_int32)]
        self.start.restype = c.c_int32
        self.end = symbol(self.lib, "uregex_end")
        self.end.argtypes = self.start.argtypes
        self.end.restype = c.c_int32
        self.error_name = symbol(self.common, "u_errorName")
        self.error_name.argtypes = [c.c_int32]
        self.error_name.restype = c.c_char_p

    def compile(self, text, should_fail=False):
        pattern, error, status = uchar(text), ParseError(), c.c_int32(0)
        compiled = self.open(pattern, len(pattern), 0, c.byref(error), c.byref(status))
        if should_fail:
            if compiled:
                self.close(compiled)
            assert status.value > 0, "The v5 crash must reproduce in the native engine"
            return self.error_name(status.value).decode(), error.offset
        if status.value > 0 or not compiled:
            raise AssertionError(f"ICU rejected {text!r}: {self.error_name(status.value).decode()} at {error.offset}")
        return compiled

    def captures(self, expression, text, group=1):
        compiled = self.compile(expression)
        data, status = uchar(text), c.c_int32(0)
        encoded = text.encode("utf-16-le")
        try:
            self.set_text(compiled, data, len(data), c.byref(status))
            result = []
            while self.find(compiled, c.byref(status)):
                start = self.start(compiled, group, c.byref(status))
                end = self.end(compiled, group, c.byref(status))
                result.append(encoded[start * 2:end * 2].decode("utf-16-le"))
            assert status.value <= 0, self.error_name(status.value).decode()
            return result
        finally:
            self.close(compiled)


def main():
    root = Path(__file__).resolve().parents[1]
    source = (root / "StremioBridge/src/main/kotlin/com/mindplayer99/stremiobridge/SourceFormatter.kt").read_text()
    icu = ICU()
    old = r"\{([A-Za-z][A-Za-z0-9]*)}"
    print("v5 crash reproduced:", *icu.compile(old, should_fail=True))
    # Read the actual Kotlin patterns, including literals passed through found().
    literal = r'("(?:[^"\\]|\\.)*")'
    expressions = [json.loads(m.group(1)) for m in re.finditer(r'\b(?:Regex|found)\(\s*' + literal, source)]
    assert len(expressions) >= 20, "No formatter patterns found"
    substitutions = {"$pattern": r"tor[- ]?box|\btb\b", "$long": r"tor[- ]?box", "$it": "English"}
    expanded = []
    for expression in expressions:
        for key, value in substitutions.items():
            expression = expression.replace(key, value)
        expanded.append(expression)
        icu.close(icu.compile(expression))
    placeholder = next(p for p in expanded if p.startswith(r"\{("))
    optional = next(p for p in expanded if p.startswith(r"\[("))
    assert icu.captures(placeholder, "▣ {resolution} ⚡ {cache}\n{originalDescription}") == ["resolution", "cache", "originalDescription"]
    assert icu.captures(placeholder, "literal braces {} stay literal") == []
    assert icu.captures(optional, "[▣ {resolution}][☁ {service}] [literal]") == ["▣ {resolution}", "☁ {service}"]
    assert icu.captures(optional, "no fields [literal]") == []
    print(f"{icu.name}: {len(expressions)} formatter pattern compilations and 4 capture checks passed")


if __name__ == "__main__":
    main()
