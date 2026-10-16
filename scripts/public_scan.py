#!/usr/bin/env python3
"""Scan tracked UTF-8 content for public-repository exclusions."""
import base64
from pathlib import Path
import re
import subprocess
import sys

DENY = base64.b64decode(
    "RmludXAs5Yeh5pmuLOa/gOWFieS7mCxMYXNlclBheSxtZXJjZWRlc2JlbnoscHVodWks5a6d5LuYLOaYk+WunSzpgJrogZQs5a+M5Y+LLOmTtuiBlCzlhYjplIs="
).decode("utf-8").casefold().split(",")
IPV4 = re.compile(r"(?<![\w.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![\w.])")
EMAIL = re.compile(r"[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@([a-z0-9-]+(?:\.[a-z0-9-]+)+)", re.I)
ALLOWED_DOMAINS = {"example.com", "example.org", "example.net", "users.noreply.github.com"}


def scan(root):
    tracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"])
    found = False
    for raw_path in tracked.split(b"\0"):
        if not raw_path:
            continue
        path = raw_path.decode("utf-8")
        if any(part in {".git", "target"} for part in Path(path).parts):
            continue
        file = root / path
        if not file.is_file():
            continue
        try:
            content = file.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(content.splitlines(), 1):
            kinds = []
            if any(all(int(octet) <= 255 for octet in match.group().split("."))
                   for match in IPV4.finditer(line)):
                kinds.append("IPv4")
            for match in EMAIL.finditer(line):
                domain = match.group(1).lower()
                if domain not in ALLOWED_DOMAINS and not domain.endswith((".example", ".invalid")):
                    kinds.append("email")
                    break
            if any(term in line.casefold() for term in DENY):
                kinds.append("deny-list")
            for kind in kinds:
                print(f"{path}:{number}: {kind}")
                found = True
    return int(found)


if __name__ == "__main__":
    sys.exit(scan(Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()))
