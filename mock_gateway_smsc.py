#!/usr/bin/env python3
"""
Full-Feature Mock SMPP Gateway (Zero-Dependency)
==============================================
This script simulates a REAL SMPP SMSC (Provider) with:
1. Binary Protocol: Supports Bind, EnquireLink, SubmitSM, and DeliverSM.
2. DLR Scheduler: High-performance min-heap based delivery receipt simulator.
3. Control API: POST /mo to inject Mobile-Originated messages.
4. CLI Interface: Interactive console for manual MO injection.

ZERO dependencies: No 'smpplib' required. Runs on standard Python 3.

Usage:
    python3 mock_gateway_smsc.py
"""

import socket
import threading
import sys
import logging
import time
import struct
import heapq
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import shlex

# =============================================================================
# CONFIGURATION
# =============================================================================
HOST = '127.0.0.1'
PORT = 2775        # Port for Kraken to connect to
CONTROL_PORT = 5556 # Port for REST API control
SYSTEM_ID = 'pavel'

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s'
)
log = logging.getLogger("SMSC-MOCK")

active_sessions = []
msg_id_counter = 0
msg_id_lock = threading.Lock()
start_time = time.time()
total_dlr_sent_count = 0

# =============================================================================
# DLR SCHEDULER (Min-Heap Worker)
# =============================================================================
class DlrScheduler:
    def __init__(self):
        self.heap = []
        self.lock = threading.Lock()
        self.condition = threading.Condition(self.lock)
        self.running = True
        self.thread = threading.Thread(target=self._worker_loop, daemon=True, name="DLR-Scheduler")
        self.thread.start()

    def schedule(self, delay_seconds, client_sock, msg_id, source, dest, status="DELIVRD"):
        due_time = time.time() + delay_seconds
        with self.lock:
            heapq.heappush(self.heap, (due_time, client_sock, msg_id, source, dest, status))
            self.condition.notify()

    def _worker_loop(self):
        global total_dlr_sent_count
        while self.running:
            with self.lock:
                now = time.time()
                if not self.heap:
                    self.condition.wait(timeout=1.0)
                    continue
                while self.heap and self.heap[0][0] <= now:
                    due_time, sock, mid, src, dst, stat = heapq.heappop(self.heap)
                    self.lock.release()
                    try:
                        send_dlr(sock, mid, dst, src, stat)
                        total_dlr_sent_count += 1
                        if total_dlr_sent_count % 100 == 0:
                            log.info(f"📊 DLR Stats: Sent {total_dlr_sent_count} total")
                    except Exception as e:
                        log.error(f"DLR Worker Error: {e}")
                    finally:
                        self.lock.acquire()
                if self.heap:
                    self.condition.wait(timeout=min(self.heap[0][0] - time.time(), 1.0))
                else:
                    self.condition.wait(timeout=1.0)

dlr_scheduler = DlrScheduler()

# =============================================================================
# SMPP BINARY PROTOCOL HANDLING
# =============================================================================

def read_pdu_header(sock):
    header = sock.recv(16)
    if not header or len(header) < 16: return None
    return struct.unpack('>IIII', header)

def get_cstring(data, offset):
    end = data.find(b'\x00', offset)
    if end == -1: return b'', offset
    return data[offset:end], end + 1

def handle_client(client_sock, addr):
    log.info(f"New SMPP connection from {addr}")
    client_sock.settimeout(60)
    try:
        while True:
            header = read_pdu_header(client_sock)
            if not header: break
            
            pdu_len, cmd_id, status, seq = header
            body = b''
            if pdu_len > 16:
                body = client_sock.recv(pdu_len - 16)

            # Bind Requests (0x01, 0x02, 0x09)
            if cmd_id in [0x01, 0x02, 0x09]:
                sys_id_bytes, _ = get_cstring(body, 0)
                sys_id = sys_id_bytes.decode(errors='ignore')
                log.info(f"Bind: '{sys_id}' (Type: 0x{cmd_id:02x})")
                
                resp_id = cmd_id | 0x80000000
                resp_body = SYSTEM_ID.encode() + b'\x00'
                client_sock.sendall(struct.pack('>IIII', 16 + len(resp_body), resp_id, 0, seq) + resp_body)
                if client_sock not in active_sessions: active_sessions.append(client_sock)
                log.info(f"✅ Session bound as {SYSTEM_ID}")

            # Enquire Link (0x15)
            elif cmd_id == 0x15:
                client_sock.sendall(struct.pack('>IIII', 16, 0x80000015, 0, seq))

            # SubmitSM (0x04)
            elif cmd_id == 0x04:
                # Basic parsing to extract dest and registered_delivery
                off = 0
                service_type, off = get_cstring(body, off)
                off += 2 # src ton/npi
                src_addr, off = get_cstring(body, off)
                off += 2 # dst ton/npi
                dst_addr, off = get_cstring(body, off)
                esm, proto, prio = body[off:off+3]; off += 3
                off += 2 # schedule, validity
                registered = body[off]; off += 1
                off += 2 # replace, coding
                default_msg_id = body[off]; off += 1
                sm_len = body[off]; off += 1
                short_message = body[off:off+sm_len]

                dest = dst_addr.decode(errors='ignore')
                log.info(f"📤 Submit_SM to {dest}: '{short_message.decode(errors='ignore')[:30]}...'")

                with msg_id_lock:
                    global msg_id_counter
                    msg_id_counter += 1
                    mid_str = f"MOCK-{int(time.time())}-{msg_id_counter:04d}"

                resp_body = mid_str.encode() + b'\x00'
                client_sock.sendall(struct.pack('>IIII', 16 + len(resp_body), 0x80000004, 0, seq) + resp_body)

                if registered & 0x01:
                    log.info(f"📝 DLR requested for {mid_str}")
                    dlr_scheduler.schedule(2.0, client_sock, mid_str, src_addr.decode(), dst_addr.decode())

            # DeliverSM Response (0x80000005)
            elif cmd_id == 0x80000005:
                log.info(f"✅ Deliver_SM_Resp received (Seq: {seq})")

            # Unbind (0x06)
            elif cmd_id == 0x06:
                client_sock.sendall(struct.pack('>IIII', 16, 0x80000006, 0, seq))
                break

    except Exception as e:
        log.error(f"Session Error: {e}")
    finally:
        if client_sock in active_sessions: active_sessions.remove(client_sock)
        client_sock.close()
        log.info(f"Connection closed for {addr}")

def build_deliver_sm_pdu(sender, recipient, text, esm_class=0x00):
    seq = int(time.time()) % 0x7FFFFFFF
    msg_bytes = text.encode('utf-8')
    
    body = bytearray([0]) # service_type
    body.extend([1, 1]) # src
    body.extend(sender.encode() + b'\x00')
    body.extend([1, 1]) # dst
    body.extend(recipient.encode() + b'\x00')
    body.extend([esm_class, 0, 0]) # esm, proto, prio
    body.extend([0, 0, 0, 0, 0, 0]) # sched, valid, reg, replace, coding, default
    body.append(len(msg_bytes))
    body.extend(msg_bytes)

    header = struct.pack('>IIII', 16 + len(body), 0x00000005, 0, seq)
    return header + bytes(body)

def send_mo(sender, recipient, text):
    if not active_sessions:
        log.warning("❌ No active sessions! Backend not connected.")
        return False
    
    pdu = build_deliver_sm_pdu(sender, recipient, text)
    for sock in active_sessions:
        try: sock.sendall(pdu)
        except: pass
    log.info(f"📨 MO Sent from {sender} to {recipient}: {text}")
    return True

def send_dlr(client_sock, msg_id, source_addr, dest_addr, status="DELIVRD"):
    date_str = datetime.now().strftime("%y%m%d%H%M")
    # DLR source/dest are inverted relative to original message
    dlr_text = f"id:{msg_id} sub:001 dlvrd:001 submit date:{date_str} done date:{date_str} stat:{status} err:000 text:Msg {status}"
    pdu = build_deliver_sm_pdu(source_addr, dest_addr, dlr_text, esm_class=0x04)
    try: client_sock.sendall(pdu)
    except: pass
    log.info(f"✅ DLR Sent for {msg_id}")

# =============================================================================
# CONTROL API (HTTP)
# =============================================================================
class ControlHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        data = json.loads(self.rfile.read(content_length).decode())
        
        if self.path == '/mo':
            if send_mo(data.get('sender', ''), data.get('recipient', ''), data.get('text', '')):
                self.send_response(200); self.end_headers()
                self.wfile.write(b'{"status": "ok"}')
            else:
                self.send_response(503); self.end_headers()
                self.wfile.write(b'{"error": "no sessions"}')
        else:
            self.send_response(404); self.end_headers()
    def log_message(self, format, *args):
        pass

def run_control_api():
    server = HTTPServer(('0.0.0.0', CONTROL_PORT), ControlHandler)
    log.info(f"🎮 Control API listening on {CONTROL_PORT}")
    server.serve_forever()

# =============================================================================
# MAIN ENTRY
# =============================================================================
def smsc_server():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((HOST, PORT))
        s.listen(5)
        log.info(f"🚀 SMPC Server listening on {HOST}:{PORT}")
        while True:
            conn, addr = s.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()

def main():
    print(f"\n{'═'*60}\n  MOCK SMPP GATEWAY (Zero-Dependency)\n{'═'*60}")
    print(f"  SMPP Server:  {HOST}:{PORT}")
    print(f"  Control API:  http://localhost:{CONTROL_PORT}/mo")
    print("-" * 60)
    print("  Commands: mo <sender> <shortcode> <text>, status, quit")
    print(f"{'═'*60}\n")

    threading.Thread(target=smsc_server, daemon=True).start()
    threading.Thread(target=run_control_api, daemon=True).start()

    try:
        while True:
            line = input("smsc> ").strip()
            if not line: continue
            cmd = shlex.split(line)
            action = cmd[0].lower()
            if action == 'mo' and len(cmd) >= 4:
                send_mo(cmd[1], cmd[2], " ".join(cmd[3:]))
            elif action == 'status':
                print(f"Connected Sessions: {len(active_sessions)}")
            elif action == 'quit': break
            elif action == 'help': print("Usage: mo <sender> <shortcode> <message>")
            else: print(f"Unknown command: {action}")
    except KeyboardInterrupt: pass
    print("\nGoodbye!")

if __name__ == "__main__":
    main()
