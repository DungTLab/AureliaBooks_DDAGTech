"""Disposable, loopback-only official Nginx proxy for the named QA harness. No VPS changes."""
import json, pathlib, socket, subprocess, sys, urllib.parse
ROOT = pathlib.Path(__file__).resolve().parents[2]
STATE = ROOT / 'target/r2-qa-proxy.json'

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def start():
    if STATE.exists():
        raise SystemExit('Proxy metadata exists; inspect/stop that QA proxy first')
    qa = json.loads((ROOT / 'target/r2-persistence-qa.json').read_text())
    ports = [free_port(), free_port()]
    servers = []
    for port, name in zip(ports, ['url', 'pausedUrl']):
        url = urllib.parse.urlparse(qa[name])
        assert url.scheme == 'http' and url.hostname == '127.0.0.1' and url.path == '/qa'
        servers.append('''server {
            listen 127.0.0.1:%d;
            client_max_body_size 6m;
            client_body_timeout 10s;
            location /qa/ {
                proxy_set_header Host $http_host;
                proxy_set_header X-Forwarded-Proto $scheme;
                proxy_set_header X-Forwarded-For $remote_addr;
                proxy_read_timeout 25s;
                proxy_pass http://127.0.0.1:%d;
            }
        }''' % (port, url.port))
    config = ROOT / 'target/r2-qa-nginx.conf'
    config.write_text('pid /tmp/nginx.pid;\nerror_log /dev/stderr warn;\nevents {}\nhttp { access_log off; client_body_temp_path /tmp/body;\n' + '\n'.join(servers) + '\n}\n')
    image = 'nginx@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236'
    container = subprocess.check_output(['docker', 'run', '-d', '--rm', '--read-only', '--network', 'host',
        '--name', 'aureliabooks-r2-e2e-proxy', '--tmpfs', '/tmp', '--tmpfs', '/var/cache/nginx', '--tmpfs', '/var/run',
        '--mount', f'type=bind,source={config},target=/etc/nginx/nginx.conf,readonly', image], text=True).strip()
    STATE.write_text(json.dumps({'container': container, 'image': image,
        'url': f'http://127.0.0.1:{ports[0]}/qa', 'pausedUrl': f'http://127.0.0.1:{ports[1]}/qa'}))
    subprocess.run(['docker', 'exec', container, 'nginx', '-t'], check=True)
    print(STATE.read_text())

def stop():
    state = json.loads(STATE.read_text())
    name = subprocess.check_output(['docker', 'inspect', '--format', '{{.Name}}', state['container']], text=True).strip()
    assert name == '/aureliabooks-r2-e2e-proxy'
    subprocess.run(['docker', 'stop', state['container']], check=True)
    STATE.unlink()

if __name__ == '__main__':
    {'start': start, 'stop': stop}[sys.argv[1]]()
