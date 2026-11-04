"""Loopback DFP/1 peer for native desktop validation; never bundled in the app.

receive: prints a port and fixture folder for the app's send_files command.
send: exercises the app's receiver, configured to a disposable --dest folder.
"""
import argparse
import hashlib
import json
from pathlib import Path
import socket
import struct
import tempfile
import time
import uuid

def frame(sock, tag, payload=b""):
    if not isinstance(payload, bytes):
        payload = json.dumps(payload).encode()
    sock.sendall(b"DFP1" + bytes([1, tag]) + struct.pack(">I", len(payload)) + payload)

def exact(sock, size):
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError("Peer closed the connection")
        data += chunk
    return data

def read(sock):
    header = exact(sock, 10)
    assert header[:5] == b"DFP1\x01"
    size = struct.unpack(">I", header[6:])[0]
    assert size <= 1024 * 1024
    return header[5], exact(sock, size)

def receiving_peer(delay):
    root = Path(tempfile.mkdtemp(prefix="dropflow-send-probe-"))
    folder = root / "Folder with spaces"
    (folder / "nested").mkdir(parents=True)
    expected = {"Folder with spaces/empty.txt": b"", "Folder with spaces/nested/写真.txt": "Hello DropFlow".encode(),
                "Folder with spaces/binary.bin": bytes(range(256)) * 4096}
    for name, data in expected.items():
        (root / name).write_bytes(data)
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0)); server.listen(1); server.settimeout(180)
        print(json.dumps({"port": server.getsockname()[1], "folder": str(folder)}), flush=True)
        conn, _ = server.accept()
        with conn:
            conn.settimeout(90)
            assert read(conn) == (10, b"HELLO DFP/1")
            frame(conn, 10, b"HELLO_ACK DFP/1")
            tag, raw = read(conn); assert tag == 1
            meta = json.loads(raw); assert meta["totalFiles"] == len(expected)
            time.sleep(delay)
            frame(conn, 2, b"ACCEPTED")
            for _ in expected:
                tag, raw = read(conn); assert tag == 4
                h = json.loads(raw); assert h["relativePath"] in expected
                frame(conn, 8, {"status": "ACCEPTED", "resumeOffset": 0})
                data = b""
                while len(data) < h["sizeBytes"]:
                    tag, raw = read(conn); assert tag == 5; data += raw
                tag, raw = read(conn); assert tag == 9
                complete = json.loads(raw)
                assert data == expected[h["relativePath"]]
                assert complete["sha256Checksum"] == hashlib.sha256(data).hexdigest()
            assert read(conn) == (6, meta["sessionId"].encode())
            # Android uses a JSON ACK, desktop historically uses plain text.
            frame(conn, 8, {"status": "ACK", "resumeOffset": 0})
        print(json.dumps({"result": "PASS", "files": len(expected), "approvalDelaySeconds": delay}), flush=True)

def send_case(port, dest, case):
    session = "probe-" + uuid.uuid4().hex
    payload = bytes(range(256)) * 4096
    name = f"probe/{case}.bin"
    declared = {"fileIndex": 0, "relativePath": name, "sizeBytes": len(payload), "sha256Checksum": ""}
    meta = {"sessionId": session, "senderId": "desktop-validation-peer", "senderName": "Validation peer", "senderPlatform": "Test",
            "totalFiles": 1, "totalSizeBytes": len(payload), "files": [declared]}
    with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
        sock.settimeout(90)
        frame(sock, 10, b"HELLO DFP/1"); assert read(sock) == (10, b"HELLO_ACK DFP/1")
        if case == "traversal":
            declared["relativePath"] = "../escape.bin"
        frame(sock, 1, meta)
        if case == "traversal":
            try: read(sock); raise AssertionError("Traversal was accepted")
            except (EOFError, ConnectionResetError): pass
        else:
            response = read(sock)[0]
            if case == "decline":
                assert response == 3
                print(json.dumps({"case": case, "result": "PASS"}), flush=True)
                return
            assert response == 2
            header = dict(declared, resumeOffset=0)
            if case == "header-mismatch": header["relativePath"] = "unapproved.bin"
            frame(sock, 4, header)
            if case == "header-mismatch":
                try: read(sock); raise AssertionError("Unapproved header was accepted")
                except (EOFError, ConnectionResetError): pass
            else:
                assert read(sock)[0] == 8
                if case == "cancel": frame(sock, 7)
                elif case == "disconnect": frame(sock, 5, payload[:1024])
                else:
                    for start in range(0, len(payload), 65536): frame(sock, 5, payload[start:start+65536])
                    digest = "0" * 64 if case == "checksum" else hashlib.sha256(payload).hexdigest()
                    frame(sock, 9, {"fileIndex": 0, "bytesWritten": len(payload), "sha256Checksum": digest})
                    if case != "checksum": frame(sock, 6, ("wrong-session" if case == "session-mismatch" else session).encode())
                if case == "success": assert read(sock) == (8, b"ACK")
                elif case != "disconnect":
                    try: read(sock); raise AssertionError(f"Invalid {case} transfer was accepted")
                    except (EOFError, ConnectionResetError): pass
    time.sleep(0.2)
    target = dest / name
    if case == "success": assert target.read_bytes() == payload
    else: assert not target.exists(), f"Failed transfer published a file: {target}"
    assert not list(dest.rglob("*.dropflow-part*")), "Partial files leaked"
    print(json.dumps({"case": case, "result": "PASS"}), flush=True)

if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("mode", choices=["receive", "send"])
    p.add_argument("--delay", type=float, default=0); p.add_argument("--port", type=int); p.add_argument("--dest", type=Path)
    p.add_argument("--case")
    args = p.parse_args()
    if args.mode == "receive": receiving_peer(args.delay)
    else:
        for case in ([args.case] if args.case else ["success", "checksum", "cancel", "disconnect", "header-mismatch", "session-mismatch", "traversal"]):
            send_case(args.port, args.dest, case)
