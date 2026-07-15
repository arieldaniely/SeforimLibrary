#!/usr/bin/env python3
"""Identify Hebrew copyright-only titles omitted from a Sefaria bulk export."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE_URL = "https://www.sefaria.org"
USER_AGENT = "SeforimLibrary-copyright-report/1.0"


def contains_hebrew(value: object) -> bool:
    return any("\u0590" <= character <= "\u05ff" for character in str(value or ""))


def fetch_json(path: str, attempts: int = 3):
    request = urllib.request.Request(
        BASE_URL + path,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError):
            if attempt + 1 == attempts:
                raise
            time.sleep(2**attempt)


def inspect_title(schema_title: str):
    encoded = urllib.parse.quote(schema_title, safe="")
    versions = fetch_json(f"/api/texts/versions/{encoded}")
    if not isinstance(versions, list):
        raise ValueError(f"Unexpected versions response for {schema_title!r}")

    hebrew_versions = [version for version in versions if version.get("language") == "he"]
    copyright_versions = [
        version
        for version in hebrew_versions
        if str(version.get("license") or "").startswith("Copyright")
    ]
    # A title is classified as copyright-only only when it has Hebrew versions
    # and every current Hebrew version carries the Copyright marker.
    if not hebrew_versions:
        return None, False
    if len(copyright_versions) != len(hebrew_versions):
        return None, True

    normalized_title = str(copyright_versions[0].get("title") or schema_title.replace("_", " "))
    try:
        index = fetch_json(f"/api/v2/raw/index/{urllib.parse.quote(normalized_title, safe='')}")
    except Exception:
        index = {}

    schema = index.get("schema") or {}
    hebrew_schema_title = next(
        (
            item.get("text")
            for item in schema.get("titles") or []
            if item.get("lang") == "he" and item.get("primary")
        ),
        None,
    )

    hebrew_title = index.get("heTitle") or schema.get("heTitle") or hebrew_schema_title
    if not contains_hebrew(hebrew_title):
        raise ValueError(f"No verified Hebrew title returned for {schema_title!r}")

    return {
        "title": normalized_title,
        "heTitle": hebrew_title,
        "categories": index.get("categories") or [],
        "versions": [
            {
                "versionTitle": version.get("versionTitle"),
                "versionTitleInHebrew": version.get("versionTitleInHebrew"),
                "language": "he",
                "actualLanguage": version.get("actualLanguage"),
                "license": version.get("license"),
                "versionSource": version.get("versionSource"),
            }
            for version in copyright_versions
        ],
    }, True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--status", required=True, type=Path)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    report = json.loads(args.input.read_text(encoding="utf-8"))
    candidates = sorted(set(report.get("schemasWithoutMergedTitles") or []))
    results = []
    errors = []
    hebrew_titles_checked = 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {executor.submit(inspect_title, title): title for title in candidates}
        for completed, future in enumerate(concurrent.futures.as_completed(futures), 1):
            title = futures[future]
            try:
                result, has_hebrew_version = future.result()
                if has_hebrew_version:
                    hebrew_titles_checked += 1
                if result is not None:
                    results.append(result)
            except Exception as error:
                errors.append({"title": title, "error": str(error)})
            if completed % 100 == 0 or completed == len(candidates):
                print(
                    f"Checked {completed}/{len(candidates)} missing Hebrew exports; "
                    f"copyright={len(results)} errors={len(errors)}",
                    flush=True,
                )

    results.sort(key=lambda item: (item.get("heTitle") or item["title"], item["title"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.status.write_text(
        json.dumps(
            {
                "candidateTitles": len(candidates),
                "checkedTitles": len(candidates) - len(errors),
                "hebrewTitlesChecked": hebrew_titles_checked,
                "requestErrors": len(errors),
                "copyrightTitles": len(results),
                "copyrightHebrewVersions": sum(len(item["versions"]) for item in results),
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    if errors:
        print(f"::warning::{len(errors)} Sefaria version requests failed; report may be partial")
    return 0


if __name__ == "__main__":
    sys.exit(main())
