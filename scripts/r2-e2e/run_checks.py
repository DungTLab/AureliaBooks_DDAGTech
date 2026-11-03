"""Start guarded QA -> Nginx -> HTTP matrix -> explicit stop -> verified cleanup.
Run from a machine with the named local QA MySQL container and canonical schema already prepared.
"""
import json, pathlib, subprocess, sys, time
ROOT=pathlib.Path(__file__).resolve().parents[2]
TARGET=ROOT/'target'
METADATA=TARGET/'r2-persistence-qa.json'
PROXY=TARGET/'r2-qa-proxy.json'
if METADATA.exists() or PROXY.exists(): raise SystemExit('An existing QA run must be inspected/stopped first')
TARGET.mkdir(exist_ok=True)
server_log=(TARGET/'r2-persistence-server.log').open('w')
server=subprocess.Popen(['bash','scripts/start-r2-persistence-qa.sh'],cwd=ROOT,stdout=server_log,stderr=subprocess.STDOUT)
status=1
try:
    deadline=time.monotonic()+60
    while not METADATA.exists():
        if server.poll() is not None: raise RuntimeError('QA bootstrap failed; inspect target/r2-persistence-server.log')
        if time.monotonic()>deadline: raise RuntimeError('QA bootstrap timeout')
        time.sleep(.25)
    subprocess.run([sys.executable,'scripts/r2-e2e/proxy.py','start'],cwd=ROOT,check=True)
    with (TARGET/'r2-persistence-http.log').open('w') as output:
        result=subprocess.run([sys.executable,'scripts/r2-e2e/http_checks.py'],cwd=ROOT,stdout=output,stderr=subprocess.STDOUT)
    print((TARGET/'r2-persistence-http-results.json').read_text())
    status=result.returncode
finally:
    (TARGET/'r2-persistence-qa.stop').touch()
    try:
        exit_code=server.wait(timeout=60)
        if exit_code: status=1
    except subprocess.TimeoutExpired:
        server.terminate(); server.wait(timeout=10); status=1
    if PROXY.exists():
        cleanup=subprocess.run([sys.executable,'scripts/r2-e2e/proxy.py','stop'],cwd=ROOT)
        if cleanup.returncode: status=1
    server_log.close()
    cleanup_file=TARGET/'r2-persistence-cleanup.json'
    if cleanup_file.exists():
        cleanup=json.loads(cleanup_file.read_text())
        print('Cleanup:',json.dumps(cleanup))
        if cleanup['productsBefore']!=cleanup['productsAfter'] or cleanup['remainingRunProducts']!=0: status=1
    else: status=1
sys.exit(status)
