#!/usr/bin/env python3
"""Offline, three-volume backups. Restore only into a fresh volume prefix."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

KINDS = ('mysql_data', 'minio_data', 'qdrant_data')
IMAGE = 'ai-knowledge-minio:2025-10-15'  # Existing Alpine image supplies tar.


def docker(*args, **kwargs):
    return subprocess.run(['docker', *args], check=True, stderr=subprocess.PIPE, **kwargs)


def output(*args):
    return docker(*args, stdout=subprocess.PIPE).stdout.decode().strip()


def names(prefix):
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{1,70}', prefix):
        raise ValueError('Use a 2–71 character lowercase volume prefix.')
    return {kind: prefix + '_' + kind for kind in KINDS}


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def stopped(volumes):
    for volume in volumes.values():
        output('volume', 'inspect', volume, '--format', '{{.Name}}')
        # Paused/restarting containers still count as active consumers.
        ids = output('ps', '-aq', '--filter', 'volume=' + volume).split()
        for container in ids:
            state = output('inspect', '--format', '{{.State.Status}}', container)
            if state not in ('exited', 'created', 'dead'):
                raise ValueError('Stop all containers using ' + volume + ' before backup.')


def archive_command(volume, read_only):
    return ['run', '--rm', '-i', '--network', 'none', '--entrypoint', 'tar',
            '--mount', 'type=volume,src=' + volume + ',dst=/data,volume-nocopy' + (',readonly' if read_only else ''),
            IMAGE]


def backup(folder, prefix):
    volumes = names(prefix)
    stopped(volumes)
    image_id = output('image', 'inspect', IMAGE, '--format', '{{.Id}}')
    folder.mkdir(parents=True, exist_ok=False, mode=0o700)
    manifest = {'format': 1, 'createdAt': datetime.now(timezone.utc).isoformat(),
                'sourcePrefix': prefix, 'archiveImageId': image_id, 'volumes': {}}
    # No manifest is published unless every archive succeeds and sources remain stopped.
    for kind, volume in volumes.items():
        stopped(volumes)
        path = folder / (kind + '.tar.gz')
        with path.open('xb') as stream:
            path.chmod(0o600)
            docker(*archive_command(volume, True), '-czpf', '-', '-C', '/data', '.', stdout=stream)
        images = []
        for container in output('ps', '-aq', '--filter', 'volume=' + volume).split():
            images.append(json.loads(output('inspect', '--format',
                '{"tag":{{json .Config.Image}},"id":{{json .Image}}}', container)))
        manifest['volumes'][kind] = {'file': path.name, 'sha256': digest(path),
                                     'bytes': path.stat().st_size, 'sourceImages': images}
    stopped(volumes)
    path = folder / 'manifest.json'
    path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    path.chmod(0o600)
    print('Backup complete:', folder)


def verify(folder):
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format') != 1 or set(manifest.get('volumes', {})) != set(KINDS):
        raise ValueError('Unsupported or incomplete backup manifest.')
    for kind in KINDS:
        item = manifest['volumes'][kind]
        if item.get('file') != kind + '.tar.gz':
            raise ValueError('Unexpected archive filename.')
        path = folder / item['file']
        if path.is_symlink() or path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
            raise ValueError('Archive integrity check failed: ' + kind)
    return manifest


def restore(folder, prefix):
    verify(folder)  # Validate every file before creating any target volume.
    volumes = names(prefix)
    existing = set(output('volume', 'ls', '-q').split())
    if existing.intersection(volumes.values()):
        raise ValueError('Target volume already exists; use a fresh prefix. Nothing was overwritten.')
    output('image', 'inspect', IMAGE, '--format', '{{.Id}}')
    for kind, volume in volumes.items():
        output('volume', 'create', '--label', 'ai-knowledge.restore=true', volume)
        with (folder / (kind + '.tar.gz')).open('rb') as stream:
            docker(*archive_command(volume, False), '-xzpf', '-', '-C', '/data', stdin=stream, stdout=subprocess.DEVNULL)
    print('Restored into new volumes: ' + ', '.join(volumes.values()))
    print('Original volumes unchanged. No services started; perform an isolated application recovery check.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('backup', 'verify', 'restore'))
    parser.add_argument('folder', type=Path)
    parser.add_argument('--prefix', help='backup source prefix or new restore target prefix')
    args = parser.parse_args()
    if args.action == 'backup':
        backup(args.folder.resolve(), args.prefix or 'ai-knowledge')
    elif args.action == 'verify':
        verify(args.folder.resolve())
        print('All three archive checksums match. This is not a database startup check.')
    else:
        if not args.prefix:
            parser.error('restore requires an explicit fresh --prefix')
        restore(args.folder.resolve(), args.prefix)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print('FAILED: ' + (str(error) if not isinstance(error, subprocess.CalledProcessError)
                           else 'Docker operation failed; check Docker and volume state.'), file=sys.stderr)
        print('Partial output may remain. No automatic deletion or overwrite was performed.', file=sys.stderr)
        sys.exit(1)
