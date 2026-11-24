import http.server
import socketserver
import json
import random
import sys

PORT = 5000

class SesmeMockHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        print(f"\n--- Incoming GET Request ---")
        for h, v in self.headers.items():
            print(f"{h}: {v}")
        
        from urllib.parse import urlparse, parse_qs
        parsed_path = urlparse(self.path)
        params = parse_qs(parsed_path.query)
        
        path = parsed_path.path
        if path == '/v1/balance':
            client_id = params.get("ClientId", [None])[0]
            api_key = params.get("ApiKey", [None])[0]
            
            print(f"ClientId (Query): {client_id}")
            print(f"ApiKey (Query): {api_key}")
            
            self.process_balance_request(client_id, api_key)
        else:
            self.send_error(404, "File Not Found")

    def do_POST(self):
        print(f"\n--- Incoming Headers ---")
        for h, v in self.headers.items():
            print(f"{h}: {v}")

        if self.path == '/v1/balance':
            # 1. Read request body safely
            content_length = int(self.headers.get('Content-Length', 0))
            transfer_encoding = self.headers.get('Transfer-Encoding', '').lower()

            if content_length > 0:
                post_data = self.rfile.read(content_length)
                try:
                    data = json.loads(post_data.decode('utf-8'))
                    client_id = data.get("ClientId")
                    api_key = data.get("ApiKey")
                except Exception as e:
                    self.send_error_response(400, {"error": "Invalid JSON", "details": str(e)})
                    return
            elif 'chunked' in transfer_encoding:
                # Basic chunked handling: just read until empty line (NOT robust but maybe enough for simple REST)
                self.send_error_response(501, {"error": "Chunked encoding not supported by this mock"})
                return
            else:
                self.send_error_response(400, {"error": "Missing POST body (Content-Length: 0)"})
                return

            # 2. Logging
            print(f"\n--- Incoming Request Body ---")
            print(f"ClientId: {client_id}")
            print(f"ApiKey: {api_key}")
            
            self.process_balance_request(client_id, api_key)
        else:
            self.send_error(404, "File Not Found")

    def process_balance_request(self, client_id, api_key):
        if not client_id or not api_key:
            self.send_error_response(400, {"error": "Missing ClientId or ApiKey (Check casing: ClientId/ApiKey)"})
            return

        # 3. Logic: Static balance of 50,000
        current_balance = 50000.0
        
        # Simulate low balance if 'low' is in ClientId
        if "low" in str(client_id).lower():
            current_balance = 5.0
            print(">>> Simulating LOW BALANCE breach")

        print(f"Returning Balance: {current_balance}")
        print(f"-----------------------")

        # 4. Send Response
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        
        response = {
            "ErrorCode": 0,
            "ErrorDescription": "Success",
            "Data": [
                {
                    "PluginType": "SMS",
                    "Credits": f"SDG{current_balance:.6f}"
                }
            ]
        }
        self.wfile.write(json.dumps(response).encode('utf-8'))

    def send_error_response(self, code, message_dict):
        self.send_response(code)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(message_dict).encode('utf-8'))

if __name__ == '__main__':
    port = PORT
    if len(sys.argv) > 1:
        port = int(sys.argv[1])

    class ReusableTCPServer(socketserver.TCPServer):
        allow_reuse_address = True

    with ReusableTCPServer(("", port), SesmeMockHandler) as httpd:
        print(f"Sesme Mock API (Hardened) running on http://localhost:{port}/v1/balance")
        print(f"Tip: To trigger an alarm in Kraken, use a ClientId containing the word 'low'")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server...")
            httpd.server_close()
