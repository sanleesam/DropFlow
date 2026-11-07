#!/usr/bin/env python3
"""Exercise Android's real SAF picker and sender against an emulator-only DFP/1 peer.

Install the debug APK and build/install its test APK with:
  ./gradlew -PdropflowInteropRunner=com.dropflow.android.InteropInstrumentation assembleDebugAndroidTest
"""
import argparse
import hashlib
import json
import os
import re
import socket
import struct
import subprocess
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--artifacts', type=Path, default=Path('/tmp/dropflow-alpha2-sender'))
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Emulator only.')
args.artifacts.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
command = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
package = 'com.dropflow.android'
fixture = 'Mac app · direct emulator test'
tree = '/sdcard/dropflow-sender-window.xml'


def adb(*params):
    return subprocess.check_output([*command, *params], timeout=25)


def nodes():
    adb('shell', 'uiautomator', 'dump', tree)
    return list(ET.fromstring(adb('shell', 'cat', tree)).iter('node'))


def find(label, scroll=False):
    labels = (label,) if isinstance(label, str) else label
    for _ in range(6 if scroll else 3):
        current = nodes()
        for node in current:
            if any(candidate in (node.get('text'), node.get('content-desc')) for candidate in labels):
                return node
        if scroll:
            area = next(node for node in current if node.get('scrollable') == 'true')
            a,b,c,d = map(int, re.findall(r'\d+', area.get('bounds')))
            adb('shell', 'input', 'swipe', str((a+c)//2), str(b+(d-b)*4//5),
                str((a+c)//2), str(b+(d-b)//5), '300')
    raise AssertionError(f'Missing UI: {label}')


def tap(label, scroll=False, long=False):
    node = find(label, scroll)
    a,b,c,d = map(int, re.findall(r'\d+', node.get('bounds')))
    x, y = str((a+c)//2), str((b+d)//2)
    if long:
        adb('shell','input','swipe',x,y,x,y,'700')
    else:
        adb('shell','input','tap',x,y)


def exact(stream, size):
    data = bytearray()
    while len(data) < size:
        chunk = stream.recv(size - len(data))
        assert chunk, 'Unexpected EOF'
        data.extend(chunk)
    return bytes(data)


def read(stream):
    header = exact(stream, 10)
    assert header[:5] == b'DFP1\x01', header
    size = struct.unpack('>I', header[6:])[0]
    assert size <= 1024 * 1024, size
    return header[5], exact(stream, size)


def frame(stream, tag, value):
    stream.sendall(b'DFP1'+bytes([1,tag])+struct.pack('>I',len(value))+value)


def peer(server, files, result):
    try:
        with server.accept()[0] as stream:
            stream.settimeout(25)
            assert read(stream) == (10, b'HELLO DFP/1')
            frame(stream, 10, b'HELLO_ACK DFP/1')
            tag, data = read(stream)
            assert tag == 1
            request = json.loads(data)
            assert request['totalFiles'] == len(files)
            assert {item['relativePath'] for item in request['files']} == set(files)
            time.sleep(17)  # exceeds the streaming socket timeout
            frame(stream, 2, b'{"status":"ACCEPTED"}')
            for index in range(len(files)):
                tag, header = read(stream)
                assert tag == 4
                info = json.loads(header)
                expected = files[info['relativePath']]
                assert info['fileIndex'] == index and info['sizeBytes'] == len(expected)
                frame(stream, 8, b'{"status":"ACCEPTED","resumeOffset":0}')
                body = bytearray()
                while len(body) < len(expected):
                    tag, data = read(stream)
                    assert tag == 5
                    body.extend(data)
                tag, data = read(stream)
                assert tag == 9 and body == expected
                completed = json.loads(data)
                assert completed['sha256Checksum'] == hashlib.sha256(expected).hexdigest()
            assert read(stream) == (6, request['sessionId'].encode())
            result['waiting_ack'] = True
            assert result['allow_ack'].wait(20), 'UI never confirmed finishing state'
            frame(stream, 8, b'{"status":"ACK"}')
            result['passed'] = True
    except Exception as exc:
        result['error'] = exc


def run():
    prefix = 'alpha2-send-'+uuid.uuid4().hex[:6]
    files = {prefix+'-payload.bin': bytes(range(256))*4096, prefix+'-empty.txt': b''}
    process = None
    result = {'allow_ack': threading.Event()}
    with socket.socket() as server, tempfile.TemporaryDirectory() as tmp:
        server.bind(('127.0.0.1', 0))
        server.listen(1)
        server.settimeout(45)
        port = server.getsockname()[1]
        adb('reverse', '--no-rebind', f'tcp:{port}', f'tcp:{port}')
        worker = threading.Thread(target=peer, args=(server, files, result), daemon=True)
        worker.start()
        try:
            for name, body in files.items():
                path = Path(tmp)/name
                path.write_bytes(body)
                adb('push', str(path), '/sdcard/Download/'+name)
            process = subprocess.Popen([*command, 'shell', 'am', 'instrument', '-w',
                '-e', 'peerHost', '127.0.0.1', '-e', 'peerPort', str(port),
                package+'.test/com.dropflow.android.InteropInstrumentation'], stdout=subprocess.PIPE)
            tap('Home')
            tap(fixture, scroll=True)
            tap('Choose files', scroll=True)
            tap('Show roots')
            tap('Downloads')
            names = list(files)
            tap(names[0], long=True)
            tap(names[1])
            tap(('Open', 'Select'))
            find('Awaiting approval')
            (args.artifacts/'awaiting-approval.png').write_bytes(adb('exec-out','screencap','-p'))
            deadline = time.monotonic() + 35
            while not result.get('waiting_ack') and not result.get('error') and time.monotonic() < deadline:
                time.sleep(.25)
            if result.get('error'):
                raise result['error']
            assert result.get('waiting_ack')
            find('Finishing')
            (args.artifacts/'finishing.png').write_bytes(adb('exec-out','screencap','-p'))
            result['allow_ack'].set()
            worker.join(8)
            assert result.get('passed'), result.get('error')
            tap('Transfers')
            find('Completed')
            print('PASS: real SAF selection, 17 s approval, 1 MiB + empty file, hashes, finishing and final ACK', flush=True)
        finally:
            result['allow_ack'].set()
            if process:
                adb('shell', 'run-as', package, 'touch', 'files/stop-interop-fixture')
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.terminate()
            adb('reverse', '--remove', f'tcp:{port}')
            for name in files:
                adb('shell', 'rm', '-f', '/sdcard/Download/'+name)


if __name__ == '__main__':
    run()
