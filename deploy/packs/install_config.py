#!/usr/bin/env python3
"""Render and transactionally apply only LinguaReader-owned Nginx/systemd files."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import sys

MARKER = '# Managed by LinguaReader packs v1'
SERVICE = 'linguareader-packs-renew.timer'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def safe_path(value):
    require(value == os.path.normpath(value) and re.fullmatch(r'/[A-Za-z0-9_./-]+', value) and all(p not in ('.', '..') for p in value.split('/')), 'Use a normalized absolute path without spaces')
    return value.rstrip('/')


def host_value(value):
    try:
        address = ipaddress.ip_address(value)
        require(address.version == 4, 'This deployment configuration currently supports IPv4')
        return str(address)
    except ValueError:
        require(not re.fullmatch(r'[0-9.]+', value), 'Invalid IPv4 address')
        require(re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?', value) and '.' in value and '..' not in value, 'Invalid IPv4 address or DNS hostname')
        return value.lower()


def configuration(host, port, public_root, service_root, nginx_root, systemd_root):
    host = host_value(host)
    require(type(port) is int and 1024 <= port <= 65535, 'Invalid dedicated TLS port')
    root, service, nginx, systemd = map(safe_path, [public_root,service_root,nginx_root,systemd_root])
    cert = service+'/letsencrypt/live/linguareader-packs'
    site = f'''{MARKER}
server {{
    listen {port} ssl;
    server_name {host};
    ssl_certificate {cert}/fullchain.pem;
    ssl_certificate_key {cert}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    root {root};
    sendfile on;
    gzip off;
    autoindex off;
    max_ranges 1;
    location = /health {{ default_type text/plain; return 200 "ok\\n"; }}
    location = /packs/index.json {{
        default_type application/json;
        add_header Cache-Control "no-store" always;
        try_files $uri =404;
    }}
    location ~ "^/packs/files/[0-9a-f]{{64}}/[A-Za-z0-9][A-Za-z0-9._-]{{0,150}}[.]lrpack$" {{
        default_type application/octet-stream;
        add_header Cache-Control "public, max-age=31536000, immutable";
        add_header X-Content-Type-Options nosniff always;
        try_files $uri =404;
    }}
    location / {{ return 404; }}
}}
'''
    hook = f'''#!/bin/sh
{MARKER}
set -eu
/usr/sbin/nginx -t
/bin/systemctl reload nginx
'''
    unit = f'''{MARKER}
[Unit]
Description=Renew LinguaReader pack certificate
[Service]
Type=oneshot
ExecStart={service}/certbot/bin/certbot renew --quiet --cert-name linguareader-packs --config-dir {service}/letsencrypt --work-dir {service}/certbot-work --logs-dir {service}/certbot-logs --deploy-hook {service}/reload-nginx.sh
'''
    timer = f'''{MARKER}
[Unit]
Description=Check LinguaReader pack certificate twice daily
[Timer]
OnCalendar=*-*-* 00,12:00:00
RandomizedDelaySec=10m
Persistent=true
[Install]
WantedBy=timers.target
'''
    state = json.dumps({'managedBy':'linguareader-packs/v1','host':host,'port':port,'publicRoot':root,'serviceRoot':service,'nginxRoot':nginx,'systemdRoot':systemd},indent=2)+'\n'
    return {
        Path(nginx)/'sites-available/linguareader-packs': ('file',site,0o644),
        Path(nginx)/'sites-enabled/linguareader-packs': ('symlink',nginx+'/sites-available/linguareader-packs',None),
        Path(service)/'reload-nginx.sh': ('file',hook,0o755),
        Path(systemd)/'linguareader-packs-renew.service': ('file',unit,0o644),
        Path(systemd)/'linguareader-packs-renew.timer': ('file',timer,0o644),
        Path(service)/'install.json': ('file',state,0o600)
    }


def snapshot(file, replacement):
    if file.is_symlink():
        require(replacement[0] == 'symlink' and os.readlink(file) == replacement[1], 'Refusing an unrelated enabled site: '+str(file))
        return ('symlink',os.readlink(file),None)
    if not file.exists():
        return None
    require(file.is_file() and replacement[0] == 'file', 'Refusing non-file: '+str(file))
    data = file.read_bytes()
    require(MARKER.encode() in data[:512] or (file.name == 'install.json' and json.loads(data).get('managedBy') == 'linguareader-packs/v1'), 'Refusing unmanaged file: '+str(file))
    return ('file',data,file.stat().st_mode & 0o777,file.stat().st_uid,file.stat().st_gid)


def replace(file, record):
    file.parent.mkdir(parents=True, exist_ok=True)
    if record is None:
        file.unlink(missing_ok=True); return
    kind, data, mode, *ownership = record
    fd, name = tempfile.mkstemp(prefix='.lr-config-', dir=file.parent); os.close(fd)
    temporary = Path(name)
    try:
        if kind == 'symlink':
            temporary.unlink(); temporary.symlink_to(data)
        else:
            temporary.write_bytes(data.encode() if isinstance(data,str) else data); temporary.chmod(mode)
            if ownership and os.geteuid()==0:os.chown(temporary,*ownership)
            with temporary.open('rb') as stream:
                os.fsync(stream.fileno())
        os.replace(temporary,file)
    finally:
        temporary.unlink(missing_ok=True)


def wait_for_health(command, timeout=10.0, runner=subprocess.run, clock=time.monotonic, sleeper=time.sleep):
    """Nginx reload is asynchronous. Retry only local connection failure, never TLS/HTTP errors."""
    deadline=clock()+timeout
    while True:
        remaining=deadline-clock()
        if remaining<=0:raise TimeoutError('Dedicated listener was not ready within '+str(timeout)+' seconds')
        result=runner(command,check=False,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=remaining)
        if result.returncode==0:
            require(result.stdout=='ok\n','Dedicated health response mismatch')
            print(result.stdout,end='');return
        if result.stderr:print(result.stderr,end='',file=sys.stderr)
        if result.returncode!=7:
            raise subprocess.CalledProcessError(result.returncode,command,output=result.stdout,stderr=result.stderr)
        remaining=deadline-clock()
        if remaining<=0:raise TimeoutError('Dedicated listener was not ready within '+str(timeout)+' seconds')
        print('Waiting for dedicated TLS listener (connection not ready)',file=sys.stderr)
        sleeper(min(0.25,remaining))


def restore_timer_state(was_enabled, was_active, attempted_enable, runner=subprocess.run):
    if not attempted_enable:return
    # Run while the candidate unit file still exists, so disable can remove its wants link.
    if not was_active:runner(['systemctl','stop',SERVICE],check=True)
    if not was_enabled:runner(['systemctl','disable',SERVICE],check=True)


def apply_managed(changes, validate, reload, restore_runtime=lambda:None, before_restore_runtime=lambda:None):
    # All ownership checks run before the first write. No other site is ever modified.
    old = {file:snapshot(file,record) for file,record in changes.items()}
    written = []
    try:
        for file,record in changes.items():
            replace(file,record); written.append(file)
        validate(); reload()
    except BaseException as initial:
        failures=[]
        try:before_restore_runtime()
        except BaseException as error:failures.append(str(error))
        for file in reversed(written):
            try:replace(file,old[file])
            except BaseException as error:failures.append(str(error))
        try:restore_runtime()
        except BaseException as error:failures.append(str(error))
        if failures:raise RuntimeError('Configuration failed and rollback needs inspection: '+ '; '.join(failures)) from initial
        raise


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host',required=True);p.add_argument('--port',type=int,default=25447)
    p.add_argument('--public-root',default='/srv/linguareader-packs/public');p.add_argument('--service-root',default='/opt/linguareader-packs')
    p.add_argument('--nginx-root',default='/etc/nginx');p.add_argument('--systemd-root',default='/etc/systemd/system')
    p.add_argument('--check-owned',action='store_true',help='Read-only validation of target names and ownership')
    p.add_argument('--render-only',help='Write reviewable configuration to an output directory, without applying it')
    a=p.parse_args();changes=configuration(a.host,a.port,a.public_root,a.service_root,a.nginx_root,a.systemd_root)
    if a.check_owned:
        for target,record in changes.items():snapshot(target,record)
        return
    if a.render_only:
        out=Path(a.render_only);out.mkdir(parents=True,exist_ok=True)
        for target,(kind,data,mode) in changes.items():
            file=out/(target.name+('.link' if kind=='symlink' else ''))
            file.write_text(data);file.chmod(mode or 0o644)
        return
    require(os.geteuid()==0,'Apply configuration through sudo')
    run=lambda *args:subprocess.run(args,check=True)
    was_enabled=subprocess.run(['systemctl','is-enabled','--quiet',SERVICE]).returncode==0
    was_active=subprocess.run(['systemctl','is-active','--quiet',SERVICE]).returncode==0
    attempted_enable=False
    def validate():
        run('nginx','-t');run('systemd-analyze','verify',str(Path(a.systemd_root)/'linguareader-packs-renew.service'),str(Path(a.systemd_root)/'linguareader-packs-renew.timer'))
    def reload():
        nonlocal attempted_enable
        run('systemctl','daemon-reload');run('systemctl','reload','nginx')
        attempted_enable=True
        run('systemctl','enable','--now',SERVICE)
        wait_for_health(('curl','--fail','--silent','--show-error','--connect-timeout','1','--max-time','2','--noproxy','*','--connect-to',f'{a.host}:{a.port}:127.0.0.1:{a.port}',f'https://{a.host}:{a.port}/health'))
    def restore():
        run('systemctl','daemon-reload')
        run('nginx','-t');run('systemctl','reload','nginx')
    apply_managed(changes,validate,reload,restore,lambda:restore_timer_state(was_enabled,was_active,attempted_enable))
    print('Installed dedicated TLS configuration; original port 80/443 sites were not changed')


if __name__=='__main__':main()
