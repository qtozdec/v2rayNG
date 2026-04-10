#!/usr/bin/env python3
"""Replace git symlink stubs inside hev-socks5-tunnel with real file copies.

On Windows without Developer Mode / admin rights, git clones symlinks as
plain text files containing the target path. ndk-build then fails with
'No such file' for hev-task-system / lwip / yaml headers. This walks the
tree and rewrites every such stub with a copy of the file it points to.

Run once after `git submodule update --init --recursive`.
"""
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "hev-socks5-tunnel"

if not ROOT.exists():
    sys.exit(f"not found: {ROOT} — run git submodule update --init --recursive first")


def is_stub(path: Path) -> bool:
    # git stores symlinks as blobs <= ~300 bytes containing only a relative path
    try:
        size = path.stat().st_size
    except OSError:
        return False
    if size == 0 or size > 1024:
        return False
    try:
        content = path.read_bytes()
    except OSError:
        return False
    if b"\n" in content.strip():
        return False
    try:
        text = content.decode("utf-8").strip()
    except UnicodeDecodeError:
        return False
    if not text or any(c in text for c in "\x00"):
        return False
    target = (path.parent / text).resolve()
    return target.exists() and target.is_file() and target != path.resolve()


fixed = 0
scanned = 0
for path in ROOT.rglob("*"):
    if not path.is_file() or ".git" in path.parts:
        continue
    scanned += 1
    if not is_stub(path):
        continue
    target_text = path.read_text().strip()
    target = (path.parent / target_text).resolve()
    data = target.read_bytes()
    path.write_bytes(data)
    fixed += 1
    print(f"  {path.relative_to(ROOT)} <- {target_text}")

print(f"scanned {scanned} files, replaced {fixed} symlink stubs")
