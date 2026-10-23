"""Verify release manifest parts before streaming the archive into tar."""
import hashlib
import json
import re
import sys
from pathlib import Path


def verify(directory):
    manifest = json.loads((directory / "seforim_bundle.tar.zst.manifest.json").read_text(encoding="utf-8"))
    if manifest.get("formatVersion") != 1 or manifest.get("file") != "seforim_bundle.tar.zst":
        raise ValueError("Unsupported seed manifest")
    parts = manifest["parts"]
    expected = [f"seforim_bundle.tar.zst.part{number:02d}" for number in range(1, len(parts) + 1)]
    if not parts or [part["file"] for part in parts] != expected:
        raise ValueError("Missing or unordered archive parts")
    combined = hashlib.sha256()
    for part in parts:
        if not re.fullmatch(r"seforim_bundle\.tar\.zst\.part[0-9]+", part["file"]):
            raise ValueError("Unsafe archive part name")
        path = directory / part["file"]
        if path.stat().st_size != part["size"]:
            raise ValueError(f"Incorrect size: {path.name}")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
                combined.update(block)
        if digest.hexdigest() != part["sha256"]:
            raise ValueError(f"Incorrect checksum: {path.name}")
    if sum(part["size"] for part in parts) != manifest["size"] or combined.hexdigest() != manifest["sha256"]:
        raise ValueError("Incorrect combined archive checksum")
    return expected


if __name__ == "__main__":
    print("\n".join(verify(Path(sys.argv[1]))))
