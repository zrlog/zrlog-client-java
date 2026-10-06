#!/usr/bin/env python3
"""Copy authoritative ZrLog contracts into the CLI's offline resource snapshots."""
import argparse
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check snapshots without writing")
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1] / "src/main/resources/openapi"
    for name, repository in (("admin-web", "zrlog-admin-web"), ("blog-web", "zrlog-blog-web-parent")):
        source = args.workspace / repository / "docs/api/openapi.yaml"
        target = destination / f"{name}.yaml"
        if not source.is_file():
            sys.exit(f"Missing authoritative contract: {source}")
        data = source.read_bytes()
        if args.check:
            if not target.is_file() or target.read_bytes() != data:
                sys.exit(f"Stale snapshot: {target}; run bin/sync-openapi.py")
        else:
            destination.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        print(f"{'Checked' if args.check else 'Updated'} {name}")


if __name__ == "__main__":
    main()
