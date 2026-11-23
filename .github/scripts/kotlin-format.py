"""Check Kotlin formatting since a Git revision, or apply it with --write."""

import argparse
import os
from pathlib import Path
import re
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args])


def edited_lines(base, path):
    patch = git("--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv",
                "--no-renames", "--unified=0", base, "--", path)
    ranges = []
    for start, count in re.findall(rb"^@@ -[0-9,]+ \+(\d+)(?:,(\d+))? @@", patch, re.M):
        length = int(count or 1)
        if length:
            ranges.append(f"{int(start)}:{int(start) + length - 1}")
    return ",".join(ranges)


def format_edits(options, base, path, write):
    # Formatting a Kotlin expression can move the boundaries of its Git hunk.
    # In write mode, include those lines until the same check passes on the result.
    while ranges := edited_lines(base, path):
        before = Path(path).read_bytes() if write else None
        status = subprocess.run([*options, "--lines=" + ranges, path]).returncode
        if status or not write or before == Path(path).read_bytes():
            return status
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", help="Base commit or tree")
    parser.add_argument("jar", type=Path, help="Path to the ktfmt executable JAR")
    parser.add_argument("--write", action="store_true", help="Apply the suggested formatting")
    args = parser.parse_args()
    base = git("rev-parse", "--verify", "--end-of-options", args.base + "^{tree}").decode().strip()
    options = [
        "java", "-jar", str(args.jar.resolve()), "--kotlinlang-style", "--enable-editorconfig",
    ]
    if not args.write:
        options += ["--dry-run", "--set-exit-if-changed"]
    records = git("diff", "--no-renames", "--name-status", "--diff-filter=AM", "-z", base,
                  "--", "*.kt", "*.kts").split(b"\0")
    new_files = []
    failed = False
    for status, raw_path in zip(records[::2], records[1::2]):
        path = "./" + os.fsdecode(raw_path)
        if Path(path).is_symlink():
            parser.error(f"Cannot format a symlink: {path}")
        if status == b"A":
            new_files.append(path)
        else:
            failed |= format_edits(options, base, path, args.write) != 0
    if new_files:
        failed |= subprocess.run([*options, *new_files]).returncode != 0
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
