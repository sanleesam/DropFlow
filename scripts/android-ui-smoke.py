"""Test-only Android emulator navigation and update-state smoke checks."""
import argparse
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', default='emulator-5554')
p.add_argument('--artifacts', type=Path, default=Path('/tmp/dropflow-alpha1-ui'))
p.add_argument('--allow-network-error', action='store_true', help='Also validate explicit DNS/timeout error UI; report availability as unverified for this run')
p.add_argument('--release', action='store_true', help='Expect the signed release identity rather than a debug build')
p.add_argument('--expect-published', action='store_true', help='Require a populated Alpha channel with no newer update')
p.add_argument('--expected-version', help='Installed version to validate; defaults to the Gradle source target')
args = p.parse_args()
expected_version = args.expected_version or re.search(r'versionName = "([^"]+)"',
    (Path(__file__).resolve().parents[1] / 'apps/android/app/build.gradle.kts').read_text()).group(1)
if not args.serial.startswith('emulator-'):
    p.error('Emulator only; never change a physical device for this check.')
adb_path = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'
args.artifacts.mkdir(parents=True, exist_ok=True)
def adb(*parts): return subprocess.check_output([str(adb_path), '-s', args.serial, *parts])
def nodes():
    for attempt in range(5):
        try:
            adb('shell', 'rm', '-f', '/sdcard/dropflow-alpha-ui.xml')
            adb('shell', 'uiautomator', 'dump', '/sdcard/dropflow-alpha-ui.xml')
            return list(ET.fromstring(adb('shell', 'cat', '/sdcard/dropflow-alpha-ui.xml')).iter('node'))
        except (subprocess.CalledProcessError, ET.ParseError):
            if attempt == 4: raise
            time.sleep(.5)
def scroll(up=False):
    area = next(n for n in nodes() if n.get('scrollable') == 'true')
    a,b,c,d = map(int, re.findall(r'\d+', area.get('bounds')))
    y1, y2 = b+(d-b)*4//5, b+(d-b)//5
    if up: y1,y2 = y2,y1
    adb('shell', 'input', 'swipe', str((a+c)//2), str(y1), str((a+c)//2), str(y2), '250')
def tap(label, seek=False):
    for attempt in range(6 if seek else 1):
        match = next((n for n in nodes() if label in (n.get('text'), n.get('content-desc'))), None)
        if match is not None:
            a,b,c,d = map(int,re.findall(r'\d+',match.get('bounds')))
            adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
            return
        if seek: scroll()
    raise AssertionError('Missing '+label)
def visible(text): return any(text in (n.get('text') or '') for n in nodes())
def shot(name): (args.artifacts/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))

adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'am', 'start', '-n', 'com.dropflow.android/.MainActivity')
tap('Home'); assert visible('No cloud required.'); shot('home')
tap('Devices'); assert visible('Search devices'); shot('devices')
tap('Transfers'); assert visible('Find a file or device'); shot('transfers')
tap('Settings'); shot('settings')
tap('Updates', seek=True); assert visible('Installed version') and visible(expected_version); shot('updates-top')
tap('Check for updates', seek=True)
deadline = time.monotonic() + 35
online = True
while time.monotonic() < deadline:
    if visible('No newer Alpha update') and args.expect_published: break
    if visible('No Alpha release published yet') and not args.expect_published: break
    if visible('Could not complete the update'):
        assert args.allow_network_error, 'Update check failed; inspect screenshot/log'
        assert visible("Couldn't reach the update server") or visible('The update server took too long'), 'Unexpected updater error'
        online = False
        break
else: raise AssertionError('Update check did not finish')
shot('updates-result')
if online:
    print('PASS: Home, Devices, Transfers, Settings, Updates; real Alpha endpoint reports ' + ('current published version' if args.expect_published else 'unpublished'), flush=True)
else:
    print('PASS: explicit connectivity error shown; remote availability not established in this run', flush=True)
tap('Back to Settings'); assert visible('Updates')
tap('About DropFlow', seek=True)
expected_label = 'Alpha' if args.release else 'Alpha · Debug build'
assert visible(expected_version) and any(n.get('text') == expected_label for n in nodes()); shot('about')
print('PASS: About reports the actual Alpha version and ' + ('release' if args.release else 'debug') + ' identity', flush=True)
tap('Back to Settings'); assert visible('About DropFlow')
print('PASS: Updates/About preserve the Settings back stack', flush=True)
tap('Home')
