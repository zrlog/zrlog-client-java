#!/usr/bin/env python3
"""Sync the zrlog-api catalog and its verified offline contract snapshots."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--workspace', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    source = args.workspace / 'zrlog-api'
    target = Path(__file__).resolve().parents[1] / 'src/main/resources/openapi'
    index = (source / 'index.json').read_bytes()
    catalog = json.loads(index)
    if catalog['schemaVersion'] != 1 or not catalog['sources']:
        raise ValueError('Unsupported or empty zrlog-api catalog')
    files = {'index.json': index}
    for entry in catalog['sources']:
        name = entry['file']
        if not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', entry['id']) or name != entry['id'] + '.yaml' or name in files:
            raise ValueError(f'Invalid or duplicate API source: {entry["id"]}')
        data = (source / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != entry['sha256']:
            raise ValueError(f'Stale zrlog-api index: {name}; regenerate index.json first')
        files[name] = data
    for name, data in files.items():
        destination = target / name
        if args.check:
            if not destination.is_file() or destination.read_bytes() != data:
                raise ValueError(f'Stale snapshot: {destination}; run bin/sync-openapi.py')
        else:
            target.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    print(f'{"Checked" if args.check else "Synced"} {len(files) - 1} contracts and zrlog-api index')


if __name__ == '__main__':
    main()
