#!/usr/bin/env python3
"""Lightweight static checks for the RouterDash single-file dashboard."""
from html.parser import HTMLParser
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "dashboard.html"
HTML = SOURCE.read_text(encoding="utf-8")


class DashboardParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
        self.scripts = []
        self.in_script = False
        self.current_script = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            self.ids.append(attrs["id"])
        if tag == "script":
            self.in_script = True
            self.current_script = []

    def handle_endtag(self, tag):
        if tag == "script" and self.in_script:
            self.scripts.append("".join(self.current_script))
            self.in_script = False

    def handle_data(self, data):
        if self.in_script:
            self.current_script.append(data)


parser = DashboardParser()
parser.feed(HTML)
parser.close()

duplicates = sorted({item for item in parser.ids if parser.ids.count(item) > 1})
if duplicates:
    raise SystemExit("Duplicate HTML ids: " + ", ".join(duplicates))

scripts = [script for script in parser.scripts if script.strip()]
if not scripts:
    raise SystemExit("No inline JavaScript found")

with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8") as js:
    js.write("\n".join(scripts))
    js.flush()
    subprocess.run(["node", "--check", js.name], check=True)

required = [
    "visibilitychange",
    "document.hidden",
    "noopener noreferrer",
    "safeServiceName",
    "AbortController",
    "min(100%,340px)",
]
missing = [token for token in required if token not in HTML]
if missing:
    raise SystemExit("Missing expected hardening/adaptive features: " + ", ".join(missing))

print("RouterDash static checks passed")
