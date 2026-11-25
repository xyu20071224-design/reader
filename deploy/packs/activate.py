#!/usr/bin/env python3
"""Verify an uploaded, checksummed release and atomically merge its catalogue (Python 3 stdlib)."""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import tempfile
import uuid
from urllib.parse import urlsplit
import zipfile
import urllib.request

MIB = 1024 ** 2
LIMITS = {'dictionary': 500*MIB, 'voice': 500*MIB, 'audio': 2048*MIB, 'bundle': 2048*MIB}
ID = re.compile(r'[a-z0-9][a-z0-9._-]{0,63}\Z')
VERSION = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,31}\Z')
NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,150}\.lrpack\Z')
SHA = re.compile(r'[0-9a-f]{64}\Z')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key: '+key)
        result[key] = value
    return result


def load_json(file):
    require(file.is_file() and not file.is_symlink() and file.stat().st_size <= MIB, 'Invalid JSON file: '+str(file))
    return json.loads(file.read_text(encoding='utf-8'), object_pairs_hook=unique_object)


def digest(file):
    h = hashlib.sha256()
    with file.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def origin(value):
    u = urlsplit(value)
    require(u.scheme == 'https' and u.hostname and not u.username and not u.password and u.path in ('', '/') and not u.query and not u.fragment, 'Expected an HTTPS origin')
    require(u.port is None or 1 <= u.port <= 65535, 'Invalid HTTPS port')
    return value.rstrip('/')


def safe_relative(value):
    require(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]*', value) and all(p not in ('', '.', '..') for p in value.split('/')), 'Unsafe deployment path: '+str(value))
    return value


def checked_file(root, relative):
    current = root
    for part in safe_relative(relative).split('/'):
        current = current/part
        require(not current.is_symlink(), 'Symlink in deployment: '+str(current))
    require(current.is_file(), 'Missing deployment file: '+str(current))
    return current


def validate_catalog(c):
    require(isinstance(c, dict) and type(c.get('formatVersion')) is int and c['formatVersion'] == 1 and isinstance(c.get('packs'), list) and len(c['packs']) <= 1000, 'Invalid catalogue')
    seen = set()
    for p in c['packs']:
        require(isinstance(p, dict) and isinstance(p.get('packId'), str) and ID.fullmatch(p['packId']) and isinstance(p.get('version'), str) and VERSION.fullmatch(p['version']), 'Invalid pack identity')
        require(p['packId'] not in seen, 'Duplicate catalogue packId'); seen.add(p['packId'])
        require(p.get('type') in LIMITS and type(p.get('bytes')) is int and 1 <= p['bytes'] <= LIMITS[p['type']] and isinstance(p.get('name'), str) and NAME.fullmatch(p['name']) and isinstance(p.get('sha256'), str) and SHA.fullmatch(p['sha256']) and type(p.get('minAppVersion')) is int and 0 <= p['minAppVersion'] <= 2147483647, 'Invalid catalogue metadata')
        require(isinstance(p.get('urls'), list) and 1 <= len(p['urls']) <= 5, 'Invalid mirrors')
        for value in p['urls']:
            require(isinstance(value, str) and not re.search(r'[\x00-\x20\x7f]', value), 'Invalid URL')
            u = urlsplit(value)
            require(u.scheme == 'https' and u.hostname and not u.username and not u.password and not u.fragment, 'Invalid HTTPS URL')
    require(isinstance(c.get('publishedVersions', {}), dict), 'Invalid version history')
    for identity, record in c.get('publishedVersions', {}).items():
        parts = identity.split('@')
        require(len(parts) == 2 and ID.fullmatch(parts[0]) and VERSION.fullmatch(parts[1]) and isinstance(record, dict) and record.get('type') in LIMITS and isinstance(record.get('sha256'), str) and SHA.fullmatch(record['sha256']) and type(record.get('bytes')) is int and 1 <= record['bytes'] <= LIMITS[record['type']], 'Invalid version history')
    require(len(json.dumps(c).encode()) <= MIB, 'Catalogue too large')
    return c


def verify_stage(stage):
    stage = Path(stage).resolve(strict=True)
    sums_file = checked_file(stage, 'SHA256SUMS')
    require(sums_file.stat().st_size <= 2*MIB, 'Checksum list too large')
    sums = sums_file.read_text(encoding='ascii').splitlines()
    require(len(sums) <= 10000, 'Too many deployed files')
    expected = {}
    for line in sums:
        require(len(line) > 66 and line[64:66] == '  ' and SHA.fullmatch(line[:64]), 'Malformed checksum line')
        name = safe_relative(line[66:]); require(name not in expected and name != 'SHA256SUMS', 'Duplicate/self checksum')
        file = checked_file(stage, name)
        require(digest(file) == line[:64], 'Upload checksum mismatch: '+name)
        expected[name] = line[:64]
    actual = set()
    for file in stage.rglob('*'):
        require(not file.is_symlink(), 'Symlink in uploaded stage')
        if file.is_file() and file != stage/'SHA256SUMS':
            actual.add(file.relative_to(stage).as_posix())
    require(actual == set(expected), 'Deployment contains missing or unchecked files')
    require({'deployment.json', 'activate.py', 'install-ubuntu.sh', 'install_config.py', 'site/packs/index.json'} <= actual, 'Incomplete deployment')
    config = load_json(stage/'deployment.json')
    require(config.get('formatVersion') == 1, 'Invalid deployment format')
    base = origin(config['baseUrl'])
    catalog = validate_catalog(load_json(stage/'site/packs/index.json'))
    for p in catalog['packs']:
        file = checked_file(stage, 'site/packs/files/'+p['sha256']+'/'+p['name'])
        require(file.stat().st_size == p['bytes'] and digest(file) == p['sha256'], 'Pack object checksum mismatch')
        require(p['urls'][0] == base+'/packs/files/'+p['sha256']+'/'+p['name'], 'Catalogue origin does not match deployment')
        with zipfile.ZipFile(file) as z:
            require(len(z.namelist()) == len(set(z.namelist())), 'Duplicate ZIP path')
            info = z.getinfo('manifest.json'); require(0 < info.file_size <= MIB, 'Invalid manifest size')
            m = json.loads(z.read(info).decode('utf-8'), object_pairs_hook=unique_object)
            require(all(m.get(k) == p[k] for k in ('packId', 'type', 'version', 'minAppVersion')) and m.get('schemaVersion') == 1, 'Archive/catalogue identity mismatch')
    return stage, config, catalog


def fsync_directory(directory):
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def managed_directory(root, relative):
    current = root
    for part in safe_relative(relative).split('/'):
        current = current/part
        require(not current.is_symlink(), 'Symlink in publication root')
        current.mkdir(exist_ok=True)
        require(current.is_dir(), 'Invalid publication directory')
    return current


@contextlib.contextmanager
def publication_lock(directory):
    lock = directory/'.publish.lock'
    body = json.dumps({'token': str(uuid.uuid4()), 'pid': os.getpid(), 'host': socket.gethostname(), 'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()})+'\n'
    try:
        fd = os.open(lock, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
    except FileExistsError:
        raise ValueError('Publication locked; inspect .publish.lock and recover manually only after its owner has stopped')
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(body); stream.flush(); os.fsync(stream.fileno())
        yield
    finally:
        if lock.is_file() and lock.read_text() == body:
            lock.unlink()


def verify_object(directory, p):
    file = checked_file(directory, 'files/'+p['sha256']+'/'+p['name'])
    require(file.stat().st_size == p['bytes'] and digest(file) == p['sha256'], 'Published object was modified: '+str(file))
    return file


def activate(stage, root, base_url=None):
    stage, config, incoming = verify_stage(stage)
    base = origin(base_url) if base_url else config['baseUrl']
    require(base == config['baseUrl'], 'Activation target differs from the checksummed deployment')
    root = Path(root); root.mkdir(parents=True, exist_ok=True); root = root.resolve(strict=True)
    directory = managed_directory(root, 'packs')
    with publication_lock(directory):
        index = directory/'index.json'
        require(not index.is_symlink(), 'Symlinked catalogue')
        current = validate_catalog(load_json(index)) if index.exists() else {'formatVersion': 1, 'packs': []}
        history = dict(current.get('publishedVersions', {}))
        def remember(identity, record):
            previous = history.get(identity)
            require(previous is None or previous == record, 'Same pack version cannot replace content: '+identity)
            history[identity] = record
        for p in current['packs']:
            verify_object(directory, p)
            remember(p['packId']+'@'+p['version'], {k: p[k] for k in ('sha256','bytes','type')})
        for identity, record in incoming.get('publishedVersions', {}).items():
            remember(identity, record)
        for p in incoming['packs']:
            remember(p['packId']+'@'+p['version'], {k: p[k] for k in ('sha256','bytes','type')})
        entries = {p['packId']: p for p in current['packs']}
        entries.update({p['packId']: p for p in incoming['packs']})
        result = {**current, 'packs': sorted(entries.values(), key=lambda p: p['packId']), 'publishedVersions': history}
        validate_catalog(result)
        for p in incoming['packs']:
            destination = managed_directory(directory, 'files/'+p['sha256'])/p['name']
            if destination.exists() or destination.is_symlink():
                verify_object(directory, p); continue
            fd, name = tempfile.mkstemp(prefix='.object-', dir=destination.parent)
            temporary = Path(name)
            try:
                with os.fdopen(fd, 'wb') as output, (stage/'site/packs/files'/p['sha256']/p['name']).open('rb') as source:
                    shutil.copyfileobj(source, output); output.flush(); os.fsync(output.fileno())
                require(temporary.stat().st_size == p['bytes'] and digest(temporary) == p['sha256'], 'Copy verification failed')
                temporary.chmod(0o644); os.replace(temporary, destination); fsync_directory(destination.parent)
            finally:
                temporary.unlink(missing_ok=True)
        without_time = lambda c: {k:v for k,v in c.items() if k != 'updatedAt'}
        if without_time(result) == without_time(current):
            return {'changed': False, 'packs': len(current['packs'])}
        result['updatedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        body = (json.dumps(result, ensure_ascii=False, indent=2)+'\n').encode('utf-8')
        require(len(body) <= MIB, 'Catalogue exceeds client byte limit')
        fd, name = tempfile.mkstemp(prefix='.index-', dir=directory); temporary = Path(name)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(body); output.flush(); os.fsync(output.fileno())
            temporary.chmod(0o644); os.replace(temporary, index); fsync_directory(directory)
        finally:
            temporary.unlink(missing_ok=True)
        return {'changed': True, 'packs': len(result['packs'])}


def check_http(root, base_url, full=False):
    directory = Path(root).resolve(strict=True)/'packs'
    index = directory/'index.json'
    catalog = validate_catalog(load_json(index))
    base = origin(base_url)
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    def get(url, headers=None):
        return opener.open(urllib.request.Request(url, headers=headers or {}), timeout=30)
    with get(base+'/health') as response:
        require(response.status == 200 and response.read(32) == b'ok\n', 'Health response mismatch')
    with get(base+'/packs/index.json') as response:
        received = response.read(MIB+1)
        require(response.status == 200 and received == index.read_bytes(), 'Served catalogue differs from activated file')
    checked = []
    for p in catalog['packs']:
        local = verify_object(directory, p)
        url = base+'/packs/files/'+p['sha256']+'/'+p['name']
        end = min(99, p['bytes']-1)
        with get(url, {'Range': 'bytes=0-'+str(end)}) as response:
            require(response.status == 206 and response.headers.get('Content-Range') == f"bytes 0-{end}/{p['bytes']}", 'Range status/header mismatch')
            with local.open('rb') as stream:
                expected = stream.read(end+1)
            require(response.read(end+2) == expected, 'Range body mismatch')
        if full:
            with get(url) as response:
                require(response.status == 200, 'Full download did not return 200')
                size = 0; h = hashlib.sha256()
                for block in iter(lambda: response.read(1024*1024), b''):
                    size += len(block); require(size <= p['bytes'], 'Full download exceeds declared size'); h.update(block)
                require(size == p['bytes'] and h.hexdigest() == p['sha256'], 'Full download checksum mismatch')
        checked.append(p['packId'])
    return {'httpVerified': True, 'rangeChecked': checked, 'fullDownloads': full}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['verify','activate','check-http'])
    parser.add_argument('--stage')
    parser.add_argument('--full',action='store_true')
    parser.add_argument('--root')
    parser.add_argument('--base-url')
    args = parser.parse_args()
    if args.command == 'check-http':
        require(args.root and args.base_url, 'check-http requires --root and --base-url')
        print(json.dumps(check_http(args.root,args.base_url,args.full)))
    elif args.command == 'verify':
        require(args.stage, 'verify requires --stage')
        _, config, catalog = verify_stage(args.stage)
        print(json.dumps({'verified': True, 'baseUrl': config['baseUrl'], 'packs': len(catalog['packs'])}))
    else:
        require(args.root and args.stage, 'activate requires --root and --stage')
        print(json.dumps(activate(args.stage, args.root, args.base_url)))


if __name__ == '__main__':
    main()
