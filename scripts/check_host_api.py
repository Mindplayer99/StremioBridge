#!/usr/bin/env python3
"""Check plugin CloudStream member references against actual, caller-supplied host APKs.

This verifies DEX signatures, including inherited members, not device playback behavior.
Usage: python3 scripts/check_host_api.py dist/StremioBridge.cs3 stable.apk pre.apk
"""
import struct
import sys
from zipfile import ZipFile


class Dex:
    def __init__(self, data):
        self.data = data
        assert data.startswith(b"dex\n"), "Not a DEX file"
        count, offset = self.uint(56), self.uint(60)
        self.strings = []
        for index in range(count):
            start = self.uint(offset + index * 4)
            _, start = self.uleb(start)
            self.strings.append(data[start:data.index(b"\0", start)].decode("utf-8", "replace"))
        self.types = [self.strings[self.uint(self.uint(68) + i * 4)] for i in range(self.uint(64))]
        self.protos = []
        for i in range(self.uint(72)):
            _, result, args = struct.unpack_from("<III", data, self.uint(76) + i * 12)
            self.protos.append("(" + "".join(self.typelist(args)) + ")" + self.types[result])
        self.fields = []
        for i in range(self.uint(80)):
            owner, kind, name = struct.unpack_from("<HHI", data, self.uint(84) + i * 8)
            self.fields.append((self.types[owner], self.strings[name], self.types[kind]))
        self.methods = []
        for i in range(self.uint(88)):
            owner, proto, name = struct.unpack_from("<HHI", data, self.uint(92) + i * 8)
            self.methods.append((self.types[owner], self.strings[name], self.protos[proto]))
        self.parents, self.defined_methods, self.defined_fields = {}, set(), set()
        for i in range(self.uint(96)):
            owner, _, parent, interfaces, _, _, members, _ = struct.unpack_from("<8I", data, self.uint(100) + i * 32)
            self.parents[self.types[owner]] = ([self.types[parent]] if parent != 0xffffffff else []) + self.typelist(interfaces)
            if not members:
                continue
            counts = []
            for _ in range(4):
                value, members = self.uleb(members)
                counts.append(value)
            for group, count in enumerate(counts):
                index = 0
                for _ in range(count):
                    delta, members = self.uleb(members)
                    index += delta
                    _, members = self.uleb(members)  # access flags
                    if group < 2:
                        self.defined_fields.add(self.fields[index])
                    else:
                        _, members = self.uleb(members)  # code offset
                        self.defined_methods.add(self.methods[index])

    def uint(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def uleb(self, offset):
        value, shift = 0, 0
        while True:
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7

    def typelist(self, offset):
        if not offset:
            return []
        return [self.types[struct.unpack_from("<H", self.data, offset + 4 + i * 2)[0]] for i in range(self.uint(offset))]


def read(path):
    with ZipFile(path) as archive:
        return [Dex(archive.read(name)) for name in archive.namelist() if name.startswith("classes") and name.endswith(".dex")]


def main():
    plugin = read(sys.argv[1])
    prefix = "Lcom/lagradost/cloudstream3/"
    methods = {member for dex in plugin for member in dex.methods if member[0].startswith(prefix)}
    fields = {member for dex in plugin for member in dex.fields if member[0].startswith(prefix)}
    failed = False
    for path in sys.argv[2:]:
        hosts = read(path)
        defined_methods = set().union(*(dex.defined_methods for dex in hosts))
        # Enum.ordinal is inherited from the Android boot classpath, outside the host APK.
        defined_methods.add(("Ljava/lang/Enum;", "ordinal", "()I"))
        defined_fields = set().union(*(dex.defined_fields for dex in hosts))
        parents = {key: value for dex in hosts for key, value in dex.parents.items()}

        def exists(member, definitions, seen=None):
            if member in definitions:
                return True
            seen = set() if seen is None else seen
            owner, name, signature = member
            if owner in seen:
                return False
            seen.add(owner)
            return any(exists((parent, name, signature), definitions, seen) for parent in parents.get(owner, []))

        missing = sorted([m for m in methods if not exists(m, defined_methods)] + [f for f in fields if not exists(f, defined_fields)])
        print(f"{path}: {len(methods)} method and {len(fields)} field references; {len(missing)} missing")
        for member in missing:
            print("  ", member)
        failed |= bool(missing)
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
