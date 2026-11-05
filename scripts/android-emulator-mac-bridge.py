#!/usr/bin/env python3
"""Manual interoperability test support, never part of a release APK.

First forward the emulator receiver: adb forward tcp:44210 tcp:42100.
Run with --host <this Mac LAN IPv4> --device-id <Android DropFlow UUID>.
Only same-Mac source addresses are allowed to connect. The bridge supplies
explicitly named test DNS records over loopback and forwards bytes unchanged
through the emulator-local port. Stop it after testing, then remove the owned
forward: adb forward --remove tcp:44210. No Wi-Fi/firewall/routing changes.
"""
import socket,struct,threading,time,signal,argparse,uuid
parser=argparse.ArgumentParser(description='Temporary same-Mac client-only bridge for Android emulator interoperability. Does not validate normal LAN discovery.')
parser.add_argument('--host',required=True,help='This Mac LAN IPv4 address; used only by local clients')
parser.add_argument('--device-id',required=True,help='Android DropFlow UUID')
parser.add_argument('--port',type=int,default=44211)
parser.add_argument('--forward-port',type=int,default=44210)
args=parser.parse_args()
socket.inet_aton(args.host)
uuid.UUID(args.device_id)
if not (1 <= args.port <= 65535 and 1 <= args.forward_port <= 65535):
 parser.error('Ports must be between 1 and 65535')
HOST=args.host
NAME='DropFlow Android direct test._dropflow._tcp.local.'
TARGET='dropflow-android-direct.local.'
UUID=args.device_id
def name(v):return b''.join(bytes([len(part)])+part.encode() for part in v.rstrip('.').split('.'))+b'\x00'
def rr(n,t,data,ttl):return name(n)+struct.pack('>HHIH',t,0x8001 if t!=12 else 1,ttl,len(data))+data
def packet(ttl=120):
 attrs=[f'device_uuid={UUID}',f'device_id={UUID}','device_name=Android emulator - direct test','device_type=phone','version=DFP/1','platform=Android','operating_system=Android emulator','application_version=0.1.0-beta.2']
 txt=b''.join(bytes([len(a.encode())])+a.encode() for a in attrs)
 records=[rr('_dropflow._tcp.local.',12,name(NAME),ttl),rr(NAME,33,struct.pack('>HHH',0,0,args.port)+name(TARGET),ttl),rr(NAME,16,txt,ttl),rr(TARGET,1,socket.inet_aton(HOST),ttl)]
 return struct.pack('>HHHHHH',0,0x8400,0,4,0,0)+b''.join(records)
def announce(ttl=120):
 with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
  s.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_IF,socket.inet_aton('127.0.0.1'))
  s.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_TTL,255)
  s.sendto(packet(ttl),('127.0.0.1',5353))
  s.sendto(packet(ttl),('224.0.0.251',5353))
def pump(a,b):
 try:
  while True:
   data=a.recv(65536)
   if not data:break
   b.sendall(data)
 except OSError:pass
 finally:
  try:b.shutdown(socket.SHUT_WR)
  except OSError:pass
def handle(client,addr):
 if addr[0] not in (HOST,'127.0.0.1'):
  client.close();return
 try:
  with client,socket.create_connection(('127.0.0.1',args.forward_port)) as guest:
   t=threading.Thread(target=pump,args=(client,guest),daemon=True);t.start()
   pump(guest,client);t.join(3)
 except OSError as e:print(e,flush=True)
def accept(server):
 while True:
  try:c,a=server.accept()
  except OSError:return
  threading.Thread(target=handle,args=(c,a),daemon=True).start()
server=socket.socket();server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);server.bind((HOST,args.port));server.listen()
threading.Thread(target=accept,args=(server,),daemon=True).start()
def stop(*_):raise SystemExit()
signal.signal(signal.SIGTERM,stop)
print('Local-only-client fixture bridge active',flush=True)
try:
 while True:announce();time.sleep(1)
finally:
 for _ in range(3):announce(0)
 server.close()
