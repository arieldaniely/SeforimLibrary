#!/usr/bin/env python3
"""Download Hebrew copyright-only Sefaria texts and their outbound links."""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import gzip
import http.client
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE_URL = "https://www.sefaria.org"
USER_AGENT = "SeforimLibrary-copyright-otzaria/1.0"


def fetch_json(path: str, query: dict[str, str] | None = None, attempts: int = 4):
    url = BASE_URL + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json", "Accept-Encoding": "gzip"},
    )
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                stream = gzip.GzipFile(fileobj=response) if response.headers.get("Content-Encoding") == "gzip" else response
                with io.TextIOWrapper(stream, encoding="utf-8") as text_stream:
                    return json.load(text_stream)
        except (
            urllib.error.HTTPError,
            urllib.error.URLError,
            http.client.IncompleteRead,
            EOFError,
            json.JSONDecodeError,
            TimeoutError,
        ):
            if attempt + 1 == attempts:
                raise
            time.sleep(2**attempt)


def find_database_export(root: Path) -> Path:
    candidates = [root, root / "database_export"]
    candidates.extend(path for path in root.glob("*/database_export"))
    for candidate in candidates:
        if (candidate / "json").is_dir() and (candidate / "schemas").is_dir():
            return candidate.resolve()
    raise FileNotFoundError(f"database_export not found under {root}")


def has_text(value: object) -> bool:
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return any(has_text(item) for item in value)
    if isinstance(value, dict):
        return any(has_text(item) for item in value.values())
    return False


def safe_schema_title(value: object) -> str:
    title = str(value or "").strip()
    if not title or title in {".", ".."} or "/" in title or "\\" in title:
        raise ValueError(f"Unsafe schema title: {title!r}")
    return title



def normalize_ref(value: object) -> str:
    ref = str(value or "").strip().replace("_", " ")
    ref = re.sub(r"(?<=\d)\.(?=\d)", ":", ref)
    return re.sub(r"\s+", " ", ref)


def matching_title(ref: str, titles: list[str]) -> str | None:
    normalized = normalize_ref(ref)
    for title in titles:
        if normalized == title or normalized.startswith(title + " ") or normalized.startswith(title + ","):
            return title
    return None


def scan_direct_links(database_export: Path, titles: list[str]) -> dict[str, list[dict]]:
    ordered_titles = sorted((normalize_ref(title) for title in titles), key=len, reverse=True)
    by_title = {title: [] for title in ordered_titles}
    seen = {title: set() for title in ordered_titles}
    links_dir = database_export / "links"
    if not links_dir.is_dir():
        raise FileNotFoundError(f"Sefaria bulk links directory not found at {links_dir}")

    for csv_path in sorted(links_dir.glob("*.csv")):
        with csv_path.open("r", encoding="utf-8", newline="") as stream:
            reader = csv.reader(stream)
            headers = next(reader, [])
            normalized_headers = [header.strip() for header in headers]
            try:
                citation1_index = normalized_headers.index("Citation 1")
                citation2_index = normalized_headers.index("Citation 2")
                connection_index = normalized_headers.index("Conection Type")
            except ValueError:
                continue
            for row in reader:
                first = normalize_ref(row[citation1_index] if citation1_index < len(row) else "")
                second = normalize_ref(row[citation2_index] if citation2_index < len(row) else "")
                connection = row[connection_index].strip() if connection_index < len(row) else ""
                first_title = matching_title(first, ordered_titles)
                second_title = matching_title(second, ordered_titles)
                pairs = []
                if first_title:
                    pairs.append((first_title, first, second))
                if second_title:
                    pairs.append((second_title, second, first))
                for title, anchor, target in pairs:
                    if not anchor or not target:
                        continue
                    key = (anchor, target, connection)
                    if key in seen[title]:
                        continue
                    seen[title].add(key)
                    by_title[title].append(
                        {"anchorRef": anchor, "sourceRef": target, "connectionType": connection or "API"}
                    )
    return by_title


def bottom_section_ref(ref: str) -> str:
    start = normalize_ref(ref).split("-", 1)[0].strip()
    return start.rsplit(":", 1)[0] if ":" in start else start


def download_links(direct_links: list[dict]) -> tuple[list[dict], list[dict]]:
    if not direct_links:
        return [], []
    section_refs = sorted({bottom_section_ref(link["anchorRef"]) for link in direct_links})
    raw_links = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        futures = {
            executor.submit(
                fetch_json,
                f"/api/links/{urllib.parse.quote(ref, safe='')}",
                {"with_text": "0"},
            ): ref
            for ref in section_refs
        }
        for future in concurrent.futures.as_completed(futures):
            section_ref = futures[future]
            try:
                response = future.result()
                if not isinstance(response, list):
                    raise ValueError("Unexpected links response")
                raw_links.extend(response)
            except Exception:
                # The per-target Text API fallback below can still resolve every
                # genuine pair found in the bulk CSV when a Links API section fails.
                continue

    api_by_pair = {}
    api_by_target = {}
    for link in raw_links:
        anchor = normalize_ref(link.get("anchorRef"))
        target = normalize_ref(link.get("sourceRef") or link.get("ref"))
        he_ref = link.get("sourceHeRef")
        if not anchor or not target or not he_ref:
            continue
        api_by_pair[(anchor, target)] = he_ref
        api_by_target.setdefault(target, he_ref)

    missing_targets = {
        normalize_ref(link["sourceRef"])
        for link in direct_links
        if normalize_ref(link["sourceRef"]) not in api_by_target
    }
    target_errors = {}
    if missing_targets:
        def fetch_he_ref(target: str) -> tuple[str, str | None]:
            encoded_target = urllib.parse.quote(target, safe="")
            failures = []
            try:
                response = fetch_json(
                    f"/api/texts/{encoded_target}",
                    {"context": "0", "commentary": "0", "pad": "1"},
                )
                he_ref = response.get("heRef") if isinstance(response, dict) else None
                if he_ref:
                    return target, he_ref
                failures.append("v1 returned no heRef")
            except Exception as error:
                failures.append(f"v1: {error}")

            try:
                response = fetch_json(
                    f"/api/v3/texts/{encoded_target}",
                    {"version": "primary"},
                )
                he_ref = response.get("heRef") if isinstance(response, dict) else None
                if he_ref:
                    return target, he_ref
                failures.append("v3 returned no heRef")
            except Exception as error:
                failures.append(f"v3: {error}")
            raise ValueError("; ".join(failures))

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            futures = {executor.submit(fetch_he_ref, target): target for target in sorted(missing_targets)}
            for future in concurrent.futures.as_completed(futures):
                target = futures[future]
                try:
                    _, he_ref = future.result()
                    if he_ref:
                        api_by_target[target] = he_ref
                    else:
                        target_errors[target] = "Text API returned no heRef"
                except Exception as error:
                    target_errors[target] = str(error)

    resolved = []
    unresolved = []
    seen_unresolved = set()
    for link in direct_links:
        anchor = normalize_ref(link["anchorRef"])
        target = normalize_ref(link["sourceRef"])
        he_ref = api_by_pair.get((anchor, target)) or api_by_target.get(target)
        if not he_ref:
            key = (anchor, target)
            if key not in seen_unresolved:
                seen_unresolved.add(key)
                unresolved.append(
                    {
                        "anchorRef": anchor,
                        "sourceRef": target,
                        "error": target_errors.get(target, "Could not resolve Hebrew ref"),
                    }
                )
            continue
        resolved.append(
            {
                "anchorRef": anchor,
                "anchorRefExpanded": [],
                "sourceRef": target,
                "sourceHeRef": he_ref,
                "connectionType": link["connectionType"],
            }
        )
    return resolved, unresolved


def merge_text(primary: object, fallback: object) -> object:
    if isinstance(primary, str):
        return primary if primary.strip() else fallback
    if isinstance(primary, list) and isinstance(fallback, list):
        return [
            merge_text(primary[index] if index < len(primary) else None, fallback[index] if index < len(fallback) else None)
            for index in range(max(len(primary), len(fallback)))
        ]
    if isinstance(primary, dict) and isinstance(fallback, dict):
        return {
            key: merge_text(primary.get(key), fallback.get(key))
            for key in primary.keys() | fallback.keys()
        }
    return primary if has_text(primary) else fallback

def download_title(item: dict, database_export: Path, direct_links: list[dict]) -> dict:
    schema_title = safe_schema_title(item.get("schemaTitle"))
    title = str(item.get("title") or "").strip()
    he_title = str(item.get("heTitle") or "").strip()
    versions = [version for version in item.get("versions") or [] if version.get("language") == "he"]
    if not title or not he_title or not versions:
        raise ValueError("Missing verified Hebrew title or version")

    text = None
    downloaded_versions = []
    for version in versions:
        version_title = str(version.get("versionTitle") or "").strip()
        if not version_title:
            raise ValueError(f"Missing Hebrew version title for {he_title}")
        text_path = "/api/texts/{}/he/{}".format(
            urllib.parse.quote(title, safe=""),
            urllib.parse.quote(version_title, safe=""),
        )
        text_response = fetch_json(
            text_path,
            {
                "context": "0",
                "commentary": "0",
                "pad": "0",
                "alts": "0",
                "fallbackOnDefaultVersion": "0",
            },
        )
        version_text = text_response.get("he") if isinstance(text_response, dict) else None
        if not has_text(version_text):
            raise ValueError(f"No Hebrew text returned for {he_title}: {version_title}")
        text = version_text if text is None else merge_text(text, version_text)
        downloaded_versions.append(version_title)
    merged_path = database_export / "json" / "__copyright_api__" / schema_title / "merged.json"
    merged_path.parent.mkdir(parents=True, exist_ok=True)
    merged_path.write_text(
        json.dumps(
            {
                "title": title,
                "heTitle": he_title,
                "language": "he",
                "versionTitle": "API merge: " + " | ".join(downloaded_versions),
                "categories": item.get("categories") or [],
                "text": text,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    links, link_errors = download_links(direct_links)

    return {
        "schemaTitle": schema_title,
        "heTitle": he_title,
        "mergedPath": str(merged_path.resolve()),
        "downloadedVersions": len(downloaded_versions),
        "links": links,
        "linkErrors": link_errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--export-root", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--links", required=True, type=Path)
    parser.add_argument("--status", required=True, type=Path)
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()

    items = json.loads(args.report.read_text(encoding="utf-8"))
    if not isinstance(items, list):
        raise ValueError("Copyright report must contain a JSON array")
    database_export = find_database_export(args.export_root)
    direct_links_by_title = scan_direct_links(
        database_export,
        [str(item.get("title") or "") for item in items],
    )
    completed = []
    errors = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {
            executor.submit(
                download_title,
                item,
                database_export,
                direct_links_by_title.get(normalize_ref(item.get("title")), []),
            ): item
            for item in items
        }
        for count, future in enumerate(concurrent.futures.as_completed(futures), 1):
            item = futures[future]
            try:
                completed.append(future.result())
            except Exception as error:
                failure = {"heTitle": item.get("heTitle"), "error": str(error)}
                errors.append(failure)
                print(f"::error::{failure['heTitle']}: {failure['error']}", flush=True)
            if count % 10 == 0 or count == len(items):
                print(f"Processed {count}/{len(items)} copyright titles; errors={len(errors)}", flush=True)

    completed.sort(key=lambda item: item["heTitle"])
    all_links = [link for item in completed for link in item["links"]]
    link_errors = [
        {"heTitle": item["heTitle"], **error}
        for item in completed
        for error in item["linkErrors"]
    ]
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(
        "".join(item["mergedPath"] + "\n" for item in completed),
        encoding="utf-8",
    )
    args.links.write_text(json.dumps(all_links, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.status.write_text(
        json.dumps(
            {
                "requestedBooks": len(items),
                "downloadedBooks": len(completed),
                "downloadedHebrewVersions": sum(item["downloadedVersions"] for item in completed),
                "directBulkLinks": sum(len(links) for links in direct_links_by_title.values()),
                "downloadedApiLinks": len(all_links),
                "unresolvedDirectLinks": len(link_errors),
                "linkErrors": link_errors,
                "errors": errors,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    if link_errors:
        print(f"::error::{len(link_errors)} direct links could not be resolved after v1 and v3 fallbacks")
        for error in link_errors[:20]:
            anchor = error.get("anchorRef") or error.get("sectionRef")
            print(
                f"::error::{error.get('heTitle')}: {anchor} -> "
                f"{error.get('sourceRef', '')}: {error.get('error')}"
            )
    if link_errors or errors:
        if errors:
            print(f"::error::{len(errors)} copyright books could not be downloaded")
        return 1
    if not completed:
        print("::error::No Hebrew copyright books were downloaded")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
