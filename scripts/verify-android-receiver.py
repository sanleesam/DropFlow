#!/usr/bin/env python3
"""Exercise DFP/1 receiving against a running Android emulator.

Install the debug APK and open DropFlow with confirmation enabled first.
Uses only emulator-local TCP forwarding; does not change host network settings.
Usage: ANDROID_HOME=/path/to/sdk python3 scripts/verify-android-receiver.py
Screenshots and results are written to --artifacts (default /tmp/dropflow-android-qa).
"""
import argparse
import hashlib
import json
import os
import re
import shlex
import socket
import struct
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--port', type=int, default=44210)
parser.add_argument('--artifacts', type=Path, default=Path('/tmp/dropflow-android-qa'))
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('These checks are intended for an emulator only.')
OUT = args.artifacts
OUT.mkdir(parents=True, exist_ok=True)
ADB = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
def adb(*params):
    return subprocess.check_output([ADB, '-s', args.serial, *params])
adb('forward', '--no-rebind', f'tcp:{args.port}', 'tcp:42100')
def nodes():
    for attempt in range(3):
        try:
            adb('shell', 'rm', '-f', '/sdcard/dropflow-window.xml')
            adb('shell', 'uiautomator', 'dump', '/sdcard/dropflow-window.xml')
            root = ET.fromstring(adb('shell', 'cat', '/sdcard/dropflow-window.xml'))
            return root.iter('node')
        except (ET.ParseError, subprocess.CalledProcessError):
            if attempt == 2:
                raise
            time.sleep(.5)
def tap(label, scroll=False):
    if scroll:
        for _ in range(5):
            current = list(nodes())
            if any(label in (n.get('text'), n.get('content-desc')) for n in current):
                break
            area = next(n for n in current if n.get('scrollable') == 'true')
            a,b,c,d = map(int, re.findall(r'\d+', area.get('bounds')))
            adb('shell', 'input', 'swipe', str((a+c)//2), str(b+(d-b)*4//5), str((a+c)//2), str(b+(d-b)//5), '300')
    for node in nodes():
        if label in (node.get('text'),node.get('content-desc')):
            a,b,c,d=map(int,re.findall(r'\d+',node.get('bounds')))
            adb('shell','input','tap',str((a+c)//2),str((b+d)//2)); return
    raise AssertionError('Missing UI: '+label)
def shot(name): (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))



def frame(s, tag, payload):
    if isinstance(payload,dict): payload=json.dumps(payload).encode()
    if isinstance(payload,str): payload=payload.encode()
    s.sendall(b'DFP1'+bytes([1,tag])+struct.pack('>I',len(payload))+payload)
def exact(s,n):
    result=b''
    while len(result)<n:
        chunk=s.recv(n-len(result))
        if not chunk: raise EOFError()
        result+=chunk
    return result
def read(s):
    h=exact(s,10)
    assert h[:5]==b'DFP1\x01',h
    return h[5],exact(s,struct.unpack('>I',h[6:])[0])
def connect():
    s=socket.create_connection(('127.0.0.1',args.port),timeout=8)
    frame(s,10,'HELLO DFP/1'); assert read(s)==(10,b'HELLO_ACK DFP/1')
    return s

def request(s, names, bodies, count=None, session_id=None):
    sid=session_id or 'qa-'+uuid.uuid4().hex[:10]
    files=[dict(fileIndex=i,relativePath=n,sizeBytes=len(b)) for i,(n,b) in enumerate(zip(names,bodies))]
    p=dict(sessionId=sid,senderId='qa-peer',senderName='MacBook Pro · Android QA',senderPlatform='macOS',
        totalFiles=len(files) if count is None else count,totalSizeBytes=sum(len(x) for x in bodies),files=files)
    frame(s,1,p)
    return sid,files

def accepted(s):
    tap('Accept'); assert read(s)[0]==2

def sendfile(s,i,name,body,bad=False):
    frame(s,4,dict(fileIndex=i,relativePath=name,sizeBytes=len(body),resumeOffset=0))
    assert read(s)[0]==8
    for start in range(0,len(body),65536): frame(s,5,body[start:start+65536])
    frame(s,9,dict(fileIndex=i,bytesWritten=len(body),sha256Checksum='0'*64 if bad else hashlib.sha256(body).hexdigest()))

def gone(s):
    try:
        tag,p=read(s)
        assert tag!=8, 'Unexpected success ACK'
    except (EOFError,ConnectionResetError): return
    finally:s.close()

def run():
    # Confirm prompts work from Settings; original beta only mounted them on Home.
    tap('Settings')
    with connect() as s:
        names=['beta2-qa-'+uuid.uuid4().hex[:6]+'.txt','beta2-empty-'+uuid.uuid4().hex[:6]+'.txt']
        bodies=[b'DropFlow Android Beta 2 verification\n',b'']
        sid,_=request(s,names,bodies); time.sleep(.3); shot('incoming-settings'); accepted(s)
        for i,(name,body) in enumerate(zip(names,bodies)): sendfile(s,i,name,body)
        frame(s,6,sid); tag,p=read(s); assert tag==8 and json.loads(p)['status']=='ACK',(tag,p)
        for n,b in zip(names,bodies): assert adb('exec-out','cat','/sdcard/Download/DropFlow/'+n)==b
    print('PASS: request on Settings, two files including empty, bytes saved before final ACK',flush=True)
    folder='alpha2-nested-'+uuid.uuid4().hex[:8]
    nested=f'{folder}/sub/Unicode-വീട്.txt'
    nested_path='/sdcard/Download/DropFlow/'+nested
    collision_path=nested_path.removesuffix('.txt')+' (1).txt'
    for body in (b'original nested bytes', b'independent collision bytes'):
        with connect() as s:
            sid,_=request(s,[nested],[body]); accepted(s)
            sendfile(s,0,nested,body); frame(s,6,sid)
            assert read(s)[0]==8
    assert adb('shell','-T','cat',shlex.quote(nested_path))==b'original nested bytes'
    assert adb('shell','-T','cat',shlex.quote(collision_path))==b'independent collision bytes'
    print('PASS: folder hierarchy and Unicode survive; duplicate names preserve both files',flush=True)
    # Decline from History.
    tap('Transfers')
    with connect() as s:
        request(s,['declined.txt'],[b'no']); tap('Decline'); assert read(s)[0]==3
    print('PASS: decline from History',flush=True)
    with connect() as s:
        request(s,['invalid-count.txt'],[b'no'],count=2); gone(s)
    print('PASS: malformed manifest rejected',flush=True)
    with connect() as s:
        request(s,['../outside.txt'],[b'no']); gone(s)
    print('PASS: path traversal rejected before receiving',flush=True)
    with connect() as s:
        sid,_=request(s,['accepted-name.txt'],[b'no']); accepted(s)
        frame(s,4,dict(fileIndex=0,relativePath='different-name.txt',sizeBytes=2)); gone(s)
    print('PASS: changed file header rejected',flush=True)
    with connect() as s:
        name='beta2-badhash-'+uuid.uuid4().hex[:6]+'.txt'
        sid,_=request(s,[name],[b'corrupt']); accepted(s); sendfile(s,0,name,b'corrupt',bad=True); gone(s)
        assert adb('shell','test','!','-e','/sdcard/Download/DropFlow/'+name)==b''
    print('PASS: bad checksum cleans up output',flush=True)
    with connect() as s:
        name='beta2-cancel-'+uuid.uuid4().hex[:6]+'.txt'
        sid,_=request(s,[name],[b'x'*100]); accepted(s)
        frame(s,4,dict(fileIndex=0,relativePath=name,sizeBytes=100)); assert read(s)[0]==8
        frame(s,5,b'x'*10); frame(s,7,sid); gone(s)
        assert adb('shell','test','!','-e','/sdcard/Download/DropFlow/'+name)==b''
    print('PASS: sender cancellation cleans up output',flush=True)
    with connect() as first:
        sid,_=request(first,['pending.txt'],[b'pending'])
        for _ in range(2):
            with connect() as second:
                request(second,['second.txt'],[b'second'],session_id=sid)
                tag,p=read(second); assert tag==3 and b'Busy' in p,(tag,p)
        tap('Decline'); assert read(first)[0]==3
    print('PASS: concurrent request rejected without replacing original prompt',flush=True)
    tap('Settings')
    tap('About DropFlow', scroll=True)
    with connect() as s:
        name = 'beta2-localcancel-' + uuid.uuid4().hex[:6] + '.txt'
        sid, _ = request(s, [name], [b'x' * 1048576])
        accepted(s)
        frame(s, 4, dict(fileIndex=0, relativePath=name, sizeBytes=1048576))
        assert read(s)[0] == 8
        frame(s, 5, b'x' * 65536)
        time.sleep(.5)
        assert any(n.get('text', '').startswith('Receiving ·') for n in nodes())
        time.sleep(2)
        shot('progress-about')
        tap('Transfers')
        assert any(n.get('text', '').startswith('Receiving ·') for n in nodes())
        tap('Cancel transfer')
        try:
            read(s)
            raise AssertionError('Local cancellation did not close the socket')
        except (EOFError, ConnectionResetError):
            pass
        time.sleep(.5)
        assert adb('shell', 'test', '!', '-e', '/sdcard/Download/DropFlow/' + name) == b''
    assert any(n.get('text') == 'Cancelled' for n in nodes())
    print('PASS: progress across About/History and local cancellation status/cleanup', flush=True)
    tap('Transfers'); shot('history-results')
    adb('shell','am','force-stop','com.dropflow.android')
    adb('shell','am','start','-n','com.dropflow.android/.MainActivity')
    tap('Transfers')
    assert any(n.get('text') == 'Cancelled' for n in nodes()), 'History missing after restart'
    tap('Clear history',scroll=True)
    assert any(n.get('text') == 'Clear transfer history?' for n in nodes())
    tap('Clear history')
    assert any(n.get('text') == 'No recent transfers' for n in nodes())
    assert adb('shell','-T','cat',shlex.quote(nested_path))==b'original nested bytes'
    print('PASS: history survives process restart; clearing it keeps received files',flush=True)
    for name in names:
        adb('shell', 'rm', '/sdcard/Download/DropFlow/' + name)
    adb('shell','rm',shlex.quote(nested_path),shlex.quote(collision_path))
    print('RECEIVER CHECKS PASSED',flush=True)
if __name__ == '__main__':
    try:
        run()
    finally:
        adb('forward', '--remove', f'tcp:{args.port}')
