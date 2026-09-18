#!/usr/bin/env python3
"""Build a release's notes from the commits it contains.

Every change here lands on main through a `Merge <branch>` commit, and the
branch says what kind of change it was - feat, fix, perf - so the notes can be
grouped without anyone having to remember a commit-message convention.

The detail comes from the commit bodies, which already explain why each change
was made. A list of subjects says what moved; the first paragraph underneath
says what was wrong with it.

    release_notes.py <previous-tag-or-empty> <tag> <server-url> <repo>
"""
from __future__ import annotations

import re
import subprocess
import sys
import textwrap

# Branch prefix -> heading, and how to count it. Order here is the order the
# sections appear. The counting words are separate from the headings because
# "Faster" is a good heading and "1 faster" is not a sentence.
SECTIONS: list[tuple[str, tuple[str, ...], str, str]] = [
    ("New features", ("feat", "feature"), "new feature", "new features"),
    ("Fixes", ("fix", "bug", "bugfix"), "fix", "fixes"),
    ("Faster", ("perf", "performance"), "speed-up", "speed-ups"),
    (
        "Behind the scenes",
        ("chore", "ci", "build", "docs", "refactor", "test"),
        "internal change",
        "internal changes",
    ),
]
OTHER = "Other changes"

# A body paragraph longer than this is cut at a sentence boundary.
DETAIL_LIMIT = 420


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=True
    ).stdout.strip()


def commits_in(range_spec: str, extra: list[str] | None = None) -> list[str]:
    """Commit hashes, oldest last, for a range. Empty when the range is empty."""
    out = git("log", "--format=%H", *(extra or []), range_spec)
    return [line for line in out.splitlines() if line]


def subject(sha: str) -> str:
    return git("log", "-1", "--format=%s", sha)


def body(sha: str) -> str:
    return git("log", "-1", "--format=%b", sha)


def is_indented(para: str) -> bool:
    """Whether a paragraph is a quoted block - an error, or a table of times."""
    return all(line.startswith("    ") for line in para.splitlines() if line.strip())


def shorten(text: str) -> str:
    if len(text) <= DETAIL_LIMIT:
        return text
    cut = text[:DETAIL_LIMIT]
    stop = max(cut.rfind(". "), cut.rfind("? "), cut.rfind("! "))
    return cut[: stop + 1] if stop > 0 else cut.rstrip() + "…"


def first_paragraph(text: str) -> str:
    """The opening paragraph, rewrapped onto one line.

    Commit bodies here are hard-wrapped at 72 columns, which renders as one
    long line on GitHub anyway - but joining them explicitly keeps the markdown
    tidy and lets the length be measured in words rather than in lines.
    """
    paragraphs = [p for p in text.split("\n\n") if p.strip()]

    for index, para in enumerate(paragraphs):
        if is_indented(para):
            continue
        cleaned = " ".join(line.strip() for line in para.splitlines() if line.strip())
        # Skip trailers and anything that is not prose.
        if not cleaned or re.match(r"^[A-Za-z-]+:\s", cleaned):
            continue

        # A paragraph introducing something - an error message, a table of
        # measurements - reads as cut off on its own, and what it introduces is
        # the interesting part.
        if cleaned.endswith(":") and index + 1 < len(paragraphs):
            following = paragraphs[index + 1]
            quoted = " ".join(
                line.strip() for line in following.splitlines() if line.strip()
            )
            if is_indented(following) and quoted:
                return shorten(f"{cleaned} `{quoted}`")
            return shorten(f"{cleaned[:-1]}. {quoted}")

        return shorten(cleaned)
    return ""


def section_for(branch: str) -> str:
    prefix = branch.split("/", 1)[0].lower()
    for heading, prefixes, _, _ in SECTIONS:
        if prefix in prefixes:
            return heading
    return OTHER


def gather(previous: str, tag: str) -> dict[str, list[tuple[str, str]]]:
    """Changes grouped by section, each a (subject, detail) pair."""
    span = f"{previous}..HEAD" if previous else "HEAD"
    grouped: dict[str, list[tuple[str, str]]] = {}
    seen: set[str] = set()

    # Merges first: the branch name is the only place the kind of change is
    # recorded, and one merge can carry several commits.
    for merge in commits_in(span, ["--merges", "--first-parent"]):
        match = re.match(r"Merge\s+(\S+)", subject(merge))
        if not match:
            continue
        heading = section_for(match.group(1))
        for sha in commits_in(f"{merge}^1..{merge}^2", ["--no-merges"]):
            if sha in seen:
                continue
            seen.add(sha)
            grouped.setdefault(heading, []).append((subject(sha), first_paragraph(body(sha))))

    # Anything committed straight onto main, which has no branch to classify it.
    for sha in commits_in(span, ["--no-merges", "--first-parent"]):
        if sha in seen:
            continue
        seen.add(sha)
        grouped.setdefault(OTHER, []).append((subject(sha), first_paragraph(body(sha))))

    return grouped


def render(grouped: dict[str, list[tuple[str, str]]], previous: str, tag: str,
           server: str, repo: str) -> str:
    total = sum(len(v) for v in grouped.values())
    out: list[str] = ["## What changed", ""]

    if total == 0:
        out += [
            f"Nothing since `{previous}` - this build is the same code." if previous
            else "The first release.",
            "",
        ]
    else:
        parts = []
        for heading, _, one, many in SECTIONS:
            found = grouped.get(heading)
            if found:
                parts.append(f"{len(found)} {one if len(found) == 1 else many}")
        other = grouped.get(OTHER)
        if other:
            parts.append(f"{len(other)} other change{'' if len(other) == 1 else 's'}")
        counts = ", ".join(parts)
        out += [f"{total} change{'s' if total != 1 else ''}" +
                (f" - {counts}." if counts else "."), ""]

        for heading in [h for h, _, _, _ in SECTIONS] + [OTHER]:
            entries = grouped.get(heading)
            if not entries:
                continue
            out += [f"### {heading}", ""]
            for line, detail in entries:
                out.append(f"**{line}**")
                if detail:
                    out += ["", textwrap.fill(detail, width=100)]
                out.append("")

    if previous:
        out += [f"**Full changelog:** {server}/{repo}/compare/{previous}...{tag}", ""]
    out += ["---", ""]
    return "\n".join(out)


def main() -> None:
    previous, tag, server, repo = sys.argv[1:5]
    print(render(gather(previous, tag), previous, tag, server, repo))


if __name__ == "__main__":
    main()
