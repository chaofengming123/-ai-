#!/usr/bin/env python3
"""Read-only smoke checks for the local lesson deployment; no model calls."""
import json
import urllib.error
import urllib.request
import re
import sys

base = sys.argv[1].rstrip('/') if len(sys.argv) > 1 else 'http://127.0.0.1:8088'

def get(path):
    try:
        with urllib.request.urlopen(base + path, timeout=15) as response:
            return response.status, response.read(), response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers

def require(condition, message):
    if not condition:
        raise SystemExit('FAIL: ' + message)

code, body, _ = get('/api/health')
require(code == 200 and json.loads(body)['status'] == 'UP', 'API forwarding')
code, home, _ = get('/')
require(code == 200 and b'<div id="app">' in home, 'Vue entry')
code, page, _ = get('/knowledge-bases')
require(code == 200 and page == home, 'Vue route refresh')
asset = re.search(rb'src="(/assets/[^\"]+\.js)"', home)
require(asset is not None, 'built JavaScript reference')
code, body, headers = get(asset.group(1).decode())
require(code == 200 and 'javascript' in headers.get('Content-Type', ''), 'built JavaScript delivery')
code, _, _ = get('/assets/nonexistent-smoke-check.js')
require(code == 404, 'missing asset must not return index.html')
for path in ['/api/auth/me', '/api/knowledge-bases']:
    code, _, headers = get(path)
    require(code == 401 and 'json' in headers.get('Content-Type', ''), 'protected API ' + path)
print('PASS: static files, route refresh, API proxy and anonymous access rejection.')
print('No accounts or documents changed; model requests were not sent.')
