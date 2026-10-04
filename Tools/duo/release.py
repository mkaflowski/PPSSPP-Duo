# Publishes a GitHub release of PPSSPP Duo with the duoOptimized APK, through the REST API (gh isn't
# installed here). The token comes from git's credential helper, never from a file.
#
#   python release.py 0.6.0 notes.md
#
# Before: bump duoVersionName/duoVersionCode in android/build.gradle.kts, commit, tag duo-v<version>,
# build twice (the first build after a tag still has the old version string inside the .so) and
# push the branch and the tag.
import json
import os
import subprocess
import sys
import urllib.request

REPO = 'mkaflowski/PPSSPP-Duo'
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
APK = os.path.join(ROOT, 'android', 'build', 'outputs', 'apk', 'duo', 'optimized', 'android-duo-optimized.apk')

version, notes = sys.argv[1], open(sys.argv[2], encoding='utf-8').read()
cred = subprocess.run(['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n',
                      capture_output=True, text=True, cwd=ROOT).stdout
token = dict(l.split('=', 1) for l in cred.splitlines() if '=' in l)['password']


def api(url, data=None, ctype='application/json'):
    req = urllib.request.Request(url, data=data, method='POST' if data is not None else 'GET')
    req.add_header('Authorization', 'token ' + token)
    req.add_header('Accept', 'application/vnd.github+json')
    if data is not None:
        req.add_header('Content-Type', ctype)
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


rel = api(f'https://api.github.com/repos/{REPO}/releases', json.dumps({
    'tag_name': f'duo-v{version}', 'name': f'PPSSPP Duo {version}', 'body': notes,
}).encode())
print('release', rel['html_url'])
name = f'PPSSPP-Duo-v{version}-arm64.apk'
asset = api(rel['upload_url'].split('{')[0] + '?name=' + name, open(APK, 'rb').read(),
            'application/vnd.android.package-archive')
print('asset', asset['browser_download_url'], asset['size'])
