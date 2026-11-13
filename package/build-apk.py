#!/usr/bin/env python3
"""
Build an OpenWrt APK v3 (ADB) package without the OpenWrt SDK.

The package contains the dashboard HTML, the LuCI controller and the menu
entry, plus install/upgrade/remove scripts that point /www/index.html at
the dashboard.

Usage: python3 build-apk.py
"""

import hashlib
import os
import shutil
import stat
import struct
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
FILES = os.path.join(ROOT, "files")
SCRIPTS = os.path.join(ROOT, "scripts")
DIST = os.path.join(ROOT, "dist")
# The dashboard itself lives in the repository root; it is copied into the
# package tree at build time so there is a single source of truth.
SRC_HTML = os.path.join(ROOT, os.pardir, "dashboard.html")

NAME = "luci-app-routerdash"
VERSION = "1.4.0-r1"
ARCH = "noarch"
DESCRIPTION = ("Тёмный веб-дашборд OpenWrt: мониторинг, Wi-Fi, клиенты, "
               "сервисы, загрузки, пакеты и логи.")
LICENSE = "MIT"
ORIGIN = "luci-app-routerdash"
MAINTAINER = "pozakonstantin-sketch"
URL = "https://github.com/pozakonstantin-sketch/luci-app-routerdash"

ADB_SCHEMA_PACKAGE = 0x676B6370  # "pckg"

# ADB value types
T_INT = 0x10000000
T_INT32 = 0x20000000
T_INT64 = 0x30000000
T_BLOB8 = 0x80000000
T_BLOB16 = 0x90000000
T_BLOB32 = 0xA0000000
T_ARRAY = 0xD0000000
T_OBJECT = 0xE0000000

# Schema field numbers (src/apk_adb.h)
PI_NAME, PI_VERSION, PI_HASHES, PI_DESCRIPTION = 1, 2, 3, 4
PI_ARCH, PI_LICENSE, PI_ORIGIN, PI_MAINTAINER, PI_URL = 5, 6, 7, 8, 9
PI_BUILD_TIME, PI_INSTALLED_SIZE, PI_DEPENDS, PI_PROVIDES = 11, 12, 15, 16

ACL_MODE, ACL_USER, ACL_GROUP = 1, 2, 3
FI_NAME, FI_ACL, FI_SIZE, FI_MTIME, FI_HASHES = 1, 2, 3, 4, 5
DI_NAME, DI_ACL, DI_FILES = 1, 2, 3
SCRIPT_SLOTS = {1: "trigger", 2: "pre-install", 3: "post-install",
                4: "pre-deinstall", 5: "post-deinstall", 6: "pre-upgrade",
                7: "post-upgrade"}
PKG_PKGINFO, PKG_PATHS, PKG_SCRIPTS = 1, 2, 3


class Adb:
    def __init__(self):
        # struct adb_hdr: compat, version, reserved, root
        self.buf = bytearray(8)

    def _alloc(self, data, align):
        pad = (-len(self.buf)) % align
        self.buf.extend(b"\0" * pad)
        off = len(self.buf)
        self.buf.extend(data)
        return off

    def blob(self, data):
        if isinstance(data, str):
            data = data.encode()
        n = len(data)
        if n == 0:
            return 0
        if n > 0xFFFF:
            return T_BLOB32 | self._alloc(struct.pack("<I", n) + data, 4)
        if n > 0xFF:
            return T_BLOB16 | self._alloc(struct.pack("<H", n) + data, 2)
        return T_BLOB8 | self._alloc(bytes([n]) + data, 1)

    def integer(self, value):
        if value < 0:
            raise ValueError("negative integer")
        if value < 0x10000000:
            return T_INT | value
        if value < 0x100000000:
            return T_INT32 | self._alloc(struct.pack("<I", value), 4)
        return T_INT64 | self._alloc(struct.pack("<Q", value), 4)

    def obj(self, fields):
        n = max(fields) + 1 if fields else 1
        arr = [b"\0\0\0\0"] * n
        arr[0] = struct.pack("<I", n)
        for slot, value in fields.items():
            arr[slot] = struct.pack("<I", value)
        return T_OBJECT | self._alloc(b"".join(arr), 4)

    def array(self, items):
        n = len(items) + 1
        arr = [struct.pack("<I", n)]
        arr += [struct.pack("<I", v) for v in items]
        return T_ARRAY | self._alloc(b"".join(arr), 4)

    def finish(self, root):
        struct.pack_into("<BBHI", self.buf, 0, 0, 0, 0, root)
        return bytes(self.buf)


def acl(adb, mode):
    return adb.obj({ACL_MODE: adb.integer(mode),
                    ACL_USER: adb.blob("root"),
                    ACL_GROUP: adb.blob("root")})


def collect_files():
    """Return list of (store_path, source_path, mode)."""
    out = []
    for dirpath, _dirnames, filenames in os.walk(FILES):
        for fn in filenames:
            src = os.path.join(dirpath, fn)
            rel = os.path.relpath(src, FILES).replace(os.sep, "/")
            mode = stat.S_IMODE(os.stat(src).st_mode) or 0o644
            out.append((rel, src, mode))
    return sorted(out)


def dependency(adb, name):
    return adb.obj({1: adb.blob(name)})


def sync_sources():
    """Copy the canonical dashboard into the package tree."""
    if not os.path.isfile(SRC_HTML):
        sys.exit("dashboard source not found: %s" % os.path.normpath(SRC_HTML))
    dest = os.path.join(FILES, "www", "dashboard.html")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.copyfile(SRC_HTML, dest)


def build():
    sync_sources()
    files = collect_files()
    if not files:
        sys.exit("no files in %s" % FILES)

    # Every parent directory that must exist.
    dirs = {"": set()}
    for rel, _src, _mode in files:
        parts = rel.split("/")
        for i in range(1, len(parts)):
            parent = "/".join(parts[:i])
            dirs.setdefault(parent, set())
        dirs["/".join(parts[:-1])].add(rel)

    adb = Adb()
    now = int(time.time())

    dir_entries = []          # (name, dir_obj, [file names])
    data_order = []           # (path_idx, file_idx, source, size)
    total_size = 0
    content_hash = hashlib.sha1()

    # Sort like apk's adb_wa_sort: by name bytes, empty root first.
    for path_idx, dirname in enumerate(sorted(dirs, key=lambda s: s.encode()), 1):
        file_names = sorted(dirs[dirname], key=lambda s: s.encode())
        file_objs = []
        for file_idx, rel in enumerate(file_names, 1):
            src = os.path.join(FILES, rel)
            name = rel.rsplit("/", 1)[-1]
            data = open(src, "rb").read()
            digest = hashlib.sha256(data).digest()
            size = len(data)
            total_size += size
            content_hash.update(data)
            file_objs.append(adb.obj({
                FI_NAME: adb.blob(name),
                FI_ACL: acl(adb, 0o644),
                FI_SIZE: adb.integer(size),
                FI_MTIME: adb.integer(now),
                FI_HASHES: adb.blob(digest),
            }))
            if size > 0:
                data_order.append((path_idx, file_idx, src, size))

        fields = {DI_ACL: acl(adb, 0o755)}
        if dirname:
            fields[DI_NAME] = adb.blob(dirname)
        if file_objs:
            fields[DI_FILES] = adb.array(file_objs)
        dir_entries.append(adb.obj(fields))

    paths_array = adb.array(dir_entries)

    script_fields = {}
    for slot, fname in SCRIPT_SLOTS.items():
        path = os.path.join(SCRIPTS, fname)
        if fname and os.path.isfile(path):
            script_fields[slot] = adb.blob(open(path, "rb").read())
    scripts_obj = adb.obj(script_fields) if script_fields else 0

    depends = adb.array([dependency(adb, "luci-base")])
    pkginfo = adb.obj({
        PI_NAME: adb.blob(NAME),
        PI_VERSION: adb.blob(VERSION),
        PI_HASHES: adb.blob(content_hash.digest()),  # package identity (SHA1)
        PI_DESCRIPTION: adb.blob(DESCRIPTION),
        PI_ARCH: adb.blob(ARCH),
        PI_LICENSE: adb.blob(LICENSE),
        PI_ORIGIN: adb.blob(ORIGIN),
        PI_MAINTAINER: adb.blob(MAINTAINER),
        PI_URL: adb.blob(URL),
        PI_BUILD_TIME: adb.integer(now),
        PI_INSTALLED_SIZE: adb.integer(total_size),
        PI_DEPENDS: depends,
    })

    pkg_fields = {PKG_PKGINFO: pkginfo, PKG_PATHS: paths_array}
    if scripts_obj:
        pkg_fields[PKG_SCRIPTS] = scripts_obj
    root = adb.obj(pkg_fields)
    payload = adb.finish(root)

    # File header + ADB block.
    out = bytearray(b"ADB." + struct.pack("<I", ADB_SCHEMA_PACKAGE))

    def add_block(kind, data):
        raw = 4 + len(data)
        out.extend(struct.pack("<I", (kind << 30) | raw))
        out.extend(data)
        out.extend(b"\0" * ((-len(out)) % 8))

    add_block(0, payload)

    # DATA blocks: 8-byte (path_idx, file_idx) header followed by content.
    for path_idx, file_idx, src, size in data_order:
        data = open(src, "rb").read()
        assert len(data) == size
        add_block(2, struct.pack("<II", path_idx, file_idx) + data)

    os.makedirs(DIST, exist_ok=True)
    out_path = os.path.join(DIST, "%s-%s.apk" % (NAME, VERSION))
    with open(out_path, "wb") as fh:
        fh.write(out)

    print("wrote %s (%d bytes, %d files, %d dirs, installed %d bytes)"
          % (out_path, len(out), len(files), len(dir_entries), total_size))


if __name__ == "__main__":
    build()
