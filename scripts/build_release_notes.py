#!/usr/bin/env python3
"""Bind published release notes to real Git tags/commits and the version in that tagged source."""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess

REPOSITORY = "xyu20071224-design/reader"


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def validate_published(data: dict) -> dict:
    if data.get("draft", False) or data.get("prerelease", False) or not data.get("published_at"):
        raise ValueError("Only already-published stable release metadata is supported")
    tag = data.get("tag_name")
    if not isinstance(tag, str) or not re.fullmatch(r"v?\d+(?:\.\d+)+", tag):
        raise ValueError("Release requires a numeric version tag")
    if data.get("html_url") != f"https://github.com/{REPOSITORY}/releases/tag/{tag}":
        raise ValueError("Release metadata belongs to another repository")
    return data


def published(path: Path) -> dict:
    return validate_published(json.loads(path.read_text(encoding="utf-8")))


def require_previous(release: dict, previous: dict, history_json: Path) -> None:
    """A complete exported metadata list makes the immediate previous stable boundary reviewable."""
    history = json.loads(history_json.read_text(encoding="utf-8"))
    if not isinstance(history, list):
        raise ValueError("Published history must be the complete exported release list")
    stable = [validate_published(item) for item in history
              if not item.get("draft", False) and not item.get("prerelease", False) and item.get("published_at")]
    matches = [item for item in stable if str(item.get("id")) == str(release.get("id"))
               and item["tag_name"] == release["tag_name"]]
    same = lambda a, b: all(a.get(key) == b.get(key) for key in
                             ("id", "tag_name", "html_url", "published_at", "draft", "prerelease", "body"))
    if len(matches) != 1 or not same(matches[0], release):
        raise ValueError("Current release must match the exported published metadata")
    timestamp = lambda item: datetime.fromisoformat(item["published_at"].replace("Z", "+00:00"))
    current_time = timestamp(release)
    if any(timestamp(item) == current_time and str(item.get("id")) != str(release.get("id")) for item in stable):
        raise ValueError("Published timestamps cannot uniquely identify the release boundary")
    earlier = [item for item in stable if timestamp(item) < current_time]
    if not earlier:
        raise ValueError("No previous published stable release was supplied")
    previous_time = max(timestamp(item) for item in earlier)
    nearest = [item for item in earlier if timestamp(item) == previous_time]
    if len(nearest) != 1 or not same(nearest[0], previous):
        raise ValueError("Previous release is not the immediately preceding published stable release")


def build(repo: Path, release_json: Path, previous_json: Path, output: Path, history_json: Path) -> dict:
    release, previous = published(release_json), published(previous_json)
    require_previous(release, previous, history_json)
    tag, previous_tag = release["tag_name"], previous["tag_name"]
    def numeric(value: str) -> tuple[int, ...]:
        parts = [int(part) for part in value.removeprefix("v").split(".")]
        while parts and parts[-1] == 0:
            parts.pop()
        return tuple(parts)
    if numeric(previous_tag) >= numeric(tag):
        raise ValueError("Previous stable release version must precede the current version")
    commit = git(repo, "rev-parse", f"refs/tags/{tag}^{{commit}}")
    old_commit = git(repo, "rev-parse", f"refs/tags/{previous_tag}^{{commit}}")
    if commit == old_commit:
        raise ValueError("Release boundaries cannot identify the same commit")
    subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", old_commit, commit], check=True)
    version_file = git(repo, "show", f"{commit}:src/app/build.gradle.kts")
    version = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', version_file, flags=re.MULTILINE)
    code = re.search(r'^\s*versionCode\s*=\s*(\d+)', version_file, flags=re.MULTILINE)
    if not version or not code or version.group(1) != tag.removeprefix("v"):
        raise ValueError("Release tag does not match the build version in its actual source")
    records = []
    for line in git(repo, "log", "--reverse", "--format=%H%x00%s", f"{old_commit}..{commit}").splitlines():
        sha, subject = line.split("\x00", 1)
        records.append(dict(commit=sha, subject=subject))
    payload = dict(schemaVersion=1, repository=REPOSITORY, releaseId=str(release["id"]),
                   releaseTag=tag, versionName=version.group(1), versionCode=int(code.group(1)),
                   sourceCommit=commit, previousReleaseTag=previous_tag, previousReleaseCommit=old_commit,
                   publishedAt=release["published_at"], notes=release.get("body") or "", commits=records)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--release-json", type=Path, required=True)
    parser.add_argument("--previous-release-json", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--published-history-json", type=Path, required=True,
                        help="Complete locally exported GitHub release metadata list, all pages")
    args = parser.parse_args()
    result = build(args.repo, args.release_json, args.previous_release_json, args.out, args.published_history_json)
    print(json.dumps({key: result[key] for key in ("releaseTag", "sourceCommit", "previousReleaseTag", "versionCode")}))


if __name__ == "__main__":
    main()
