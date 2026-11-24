#!/usr/bin/env python3
"""Build a local, immutable static mirror from downloaded original release assets. Never uploads."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import quote, unquote, urlsplit

REPOSITORY = "xyu20071224-design/reader"
MAX_PAGE_BYTES = 4 * 1024 * 1024


def safe_component(value: str) -> str:
    if not isinstance(value, str) or not value.strip() or value in {".", ".."}:
        raise ValueError("Empty or unsafe release/asset name")
    if any(c in value for c in "/\\") or any(ord(c) < 32 for c in value):
        raise ValueError("Release and asset names must be single path components")
    return value


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def canonical_asset(url: str, tag: str, name: str) -> bool:
    parsed = urlsplit(url)
    return (parsed.scheme == "https" and parsed.hostname == "github.com" and
            parsed.port in {None, 443} and parsed.username is None and
            not parsed.query and not parsed.fragment and
            not re.search(r"%(?:2f|5c|25)", parsed.path, flags=re.IGNORECASE) and
            unquote(parsed.path) == f"/{REPOSITORY}/releases/download/{tag}/{name}")


def atomic_bytes(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".mirror-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def json_bytes(value: object) -> bytes:
    payload = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(payload) > MAX_PAGE_BYTES:
        raise ValueError("Catalog page exceeds client metadata limit")
    return payload


def build(release_json: Path, assets_dir: Path, output: Path, page_size: int = 50,
          latest_tag: str | None = None) -> dict:
    if not 1 <= page_size <= 100:
        raise ValueError("Page size must be 1..100")
    raw = json.loads(release_json.read_text(encoding="utf-8"))
    releases = raw if isinstance(raw, list) else [raw]
    assets_root = assets_dir.resolve()
    records = []
    copies = []
    ids, tags = set(), set()
    for release in releases:
        if release.get("draft", False):
            continue
        tag = safe_component(release["tag_name"])
        release_id = str(release.get("id") or f"tag:{tag}")
        if tag in tags or release_id in ids:
            raise ValueError("Duplicate release tag or ID")
        tags.add(tag)
        ids.add(release_id)
        assets, names = [], set()
        for asset in release.get("assets", []):
            name = safe_component(asset["name"])
            if name in names:
                raise ValueError("Duplicate asset name")
            names.add(name)
            canonical = asset["browser_download_url"]
            if not canonical_asset(canonical, tag, name):
                raise ValueError("Asset URL does not match its original release")
            source = (assets_root / tag / name).resolve()
            if not source.is_relative_to(assets_root) or not source.is_file():
                raise ValueError(f"Missing original asset: {tag}/{name}")
            size = source.stat().st_size
            if size <= 0 or ("size" in asset and asset["size"] != size):
                raise ValueError(f"Original asset size mismatch: {tag}/{name}")
            sha = digest(source)
            expected = asset.get("digest")
            if expected is not None and expected != f"sha256:{sha}":
                raise ValueError(f"Original asset digest mismatch: {tag}/{name}")
            relative = f"assets/{tag}/{name}"
            assets.append(dict(name=name, canonicalUrl=canonical, url=relative, bytes=size, sha256=sha))
            copies.append((source, relative, sha))
        records.append(dict(id=release_id, tag=tag, notes=release.get("body") or "",
                            publishedAt=release.get("published_at") or "",
                            targetCommitish=release.get("target_commitish") or "",
                            draft=False, prerelease=bool(release.get("prerelease", False)), assets=assets))
    records.sort(key=lambda r: (r["publishedAt"], r["tag"]), reverse=True)
    stable = [r for r in records if not r["prerelease"]]
    if latest_tag is None:
        latest_tag = stable[0]["tag"] if stable else None
    if latest_tag is not None and latest_tag not in {r["tag"] for r in stable}:
        raise ValueError("Latest tag must identify a mirrored published stable release")
    logical = dict(repository=REPOSITORY, latestTag=latest_tag, releases=records)
    generation = hashlib.sha256(json.dumps(logical, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()[:24]
    prefix = f"generations/{generation}"
    for record in records:
        for asset in record["assets"]:
            asset["url"] = quote(f"{prefix}/{asset['url']}", safe="/")
    chunks = [records[i:i + page_size] for i in range(0, len(records), page_size)] or [[]]
    pages = []
    for index, chunk in enumerate(chunks):
        next_page = f"{prefix}/page-{index + 2}.json" if index + 1 < len(chunks) else None
        pages.append(json_bytes(dict(schemaVersion=1, repository=REPOSITORY, latestTag=latest_tag,
                                     releases=chunk, nextPage=next_page)))
    # Every new catalog references an immutable generation. Only switch the root after all bytes exist.
    output.mkdir(parents=True, exist_ok=True)
    for source, relative, sha in copies:
        destination = output / prefix / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".asset-", dir=destination.parent)
        os.close(fd)
        try:
            shutil.copyfile(source, temporary)
            if digest(Path(temporary)) != sha:
                raise ValueError("Original asset changed while copying")
            os.replace(temporary, destination)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    for index, payload in enumerate(pages[1:], start=2):
        atomic_bytes(output / prefix / f"page-{index}.json", payload)
    atomic_bytes(output / "catalog.json", pages[0])
    return dict(generation=generation, releases=len(records), assets=len(copies), pages=len(pages),
                latestTag=latest_tag, catalog=str(output / "catalog.json"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-json", type=Path, required=True,
                        help="Downloaded GitHub release object or list; no credentials needed")
    parser.add_argument("--assets-dir", type=Path, required=True,
                        help="Original downloaded assets under <tag>/<asset name>")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--page-size", type=int, default=50)
    parser.add_argument("--latest-tag", help="Set to GitHub's actual latest stable tag if different from publication order")
    args = parser.parse_args()
    print(json.dumps(build(args.release_json, args.assets_dir, args.out, args.page_size, args.latest_tag), ensure_ascii=False))


if __name__ == "__main__":
    main()
