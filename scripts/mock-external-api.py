#!/usr/bin/env python3
import argparse
import json
import time
import random
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

class MockExternalAPIHandler(BaseHTTPRequestHandler):
    fail_rate = 0.05
    delay = 0.1
    timeout_rate = 0.02
    
    def do_POST(self):
        # Read the request body
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)
        
        try:
            req_json = json.loads(post_data.decode('utf-8'))
            msisdn = req_json.get("msisdn", "unknown")
            print(f"[{time.strftime('%H:%M:%S')}] Received request for {msisdn}")
        except:
            print(f"[{time.strftime('%H:%M:%S')}] Received non-JSON or empty request")

        # Simulate timeout
        if random.random() < self.timeout_rate:
            print("  -> Simulating timeout...")
            time.sleep(10)
            return

        # Simulate latency
        time.sleep(self.delay)

        # Simulate failure
        if random.random() < self.fail_rate:
            print("  -> Simulating error response (500)")
            self.send_response(500)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            response = {"status": "error", "errorDescription": "Internal Server Error"}
            self.wfile.write(json.dumps(response).encode('utf-8'))
            return

        # Success response
        print("  -> Returning success response")
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        
        balances = ["1500", "200.50", "0", "15.00"]
        
        response = {
            "status": "success",
            "message": f"Your current balance is {random.choice(balances)} SDG.",
            "transactionId": str(random.randint(100000, 999999))
        }
        
        self.wfile.write(json.dumps(response).encode('utf-8'))

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    """Handle requests in a separate thread."""

def main():
    parser = argparse.ArgumentParser(description="Mock External API Server")
    parser.add_argument("--port", type=int, default=9090, help="Port to listen on")
    parser.add_argument("--fail-rate", type=float, default=0.05, help="Failure probability (0.0 to 1.0)")
    parser.add_argument("--delay", type=float, default=0.1, help="Response delay in seconds")
    parser.add_argument("--timeout-rate", type=float, default=0.02, help="Timeout probability (0.0 to 1.0)")
    args = parser.parse_args()

    MockExternalAPIHandler.fail_rate = args.fail_rate
    MockExternalAPIHandler.delay = args.delay
    MockExternalAPIHandler.timeout_rate = args.timeout_rate

    server = ThreadedHTTPServer(('0.0.0.0', args.port), MockExternalAPIHandler)
    print(f"Starting mock external API on port {args.port}...")
    print(f"Settings: delay={args.delay}s, fail_rate={args.fail_rate*100}%, timeout_rate={args.timeout_rate*100}%")
    
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping server...")
        server.server_close()

if __name__ == "__main__":
    main()
