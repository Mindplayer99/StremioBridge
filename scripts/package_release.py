#!/usr/bin/env python3
"""Verify the compiled plugin and produce self-hostable CloudStream repository files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
from urllib.parse import urlsplit
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
repository = os.environ.get("GITHUB_REPOSITORY", "Mindplayer99/StremioBridge")
parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default=f"https://raw.githubusercontent.com/{repository}/main/dist")
parser.add_argument("--out", type=Path, default=root / "dist")
args = parser.parse_args()
base = args.base_url.rstrip("/")
url = urlsplit(base)
if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password or url.query or url.fragment:
    parser.error("Provide a directory URL without credentials, query or fragment.")

binary = root / "StremioBridge/build/StremioBridge.cs3"
index = json.loads((root / "build/plugins.json").read_text())
if len(index) != 1 or index[0]["internalName"] != "StremioBridge":
    raise SystemExit("Unexpected plugin index")
entry = index[0]
checksum = "sha256-" + hashlib.sha256(binary.read_bytes()).hexdigest()
if entry["fileHash"] != checksum or entry["fileSize"] != binary.stat().st_size:
    raise SystemExit("Checksum or size mismatch")
with ZipFile(binary) as archive:
    if archive.testzip() or not archive.read("classes.dex").startswith(b"dex\n"):
        raise SystemExit("Invalid plugin DEX or archive")
    manifest = json.loads(archive.read("manifest.json"))
    if manifest["pluginClassName"] != "com.mindplayer99.stremiobridge.StremioBridgePlugin" or manifest["version"] != entry["version"]:
        raise SystemExit("Incorrect entry point or version")

entry["url"] = base + "/" + binary.name
repo = {"name": "StremioBridge", "description": "Native Stremio addon profiles for CloudStream",
        "manifestVersion": 1, "pluginLists": [base + "/plugins.json"]}
args.out.mkdir(parents=True, exist_ok=True)
shutil.copyfile(binary, args.out / binary.name)
for name, data in (("plugins.json", index), ("repo.json", repo)):
    (args.out / name).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
print(f"{binary.name}  {entry['fileSize']} bytes  {checksum}")
