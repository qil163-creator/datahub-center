#!/usr/bin/env python3
"""将宿主环回端口的原始字节送入现有容器，不记录内容或持有服务端私钥。"""

import argparse
import json
import os
from pathlib import Path
import select
import socketserver
import subprocess

RELAY = '''import os,select,socket,sys
s=socket.create_connection(("127.0.0.1",int(sys.argv[1])),timeout=10)
s.settimeout(None)
while True:
 ready,_,_=select.select([0,s],[],[])
 for source in ready:
  data=os.read(0,65536) if source==0 else s.recv(65536)
  if not data: raise SystemExit(0)
  if source==0: s.sendall(data)
  else: os.write(1,data)
'''


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        process = subprocess.Popen(["docker", "exec", "-i", self.server.container, "python3", "-c", RELAY, str(self.server.target_port)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            while True:
                ready, _, _ = select.select([self.request, process.stdout], [], [], 30)
                if not ready:
                    return
                for source in ready:
                    payload = self.request.recv(65536) if source is self.request else os.read(process.stdout.fileno(), 65536)
                    if not payload:
                        return
                    if source is self.request:
                        os.write(process.stdin.fileno(), payload)
                    else:
                        self.request.sendall(payload)
        except OSError:
            pass
        finally:
            process.terminate()
            process.wait(timeout=5)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="datahub-ds-local-standalone-1")
    parser.add_argument("--port", type=int, default=19101)
    parser.add_argument("--target-port", type=int, default=19101)
    parser.add_argument("--state-file", type=Path)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 1 <= args.target_port <= 65535:
        parser.error('端口必须在1至65535之间')
    server = Server(("127.0.0.1", args.port), Handler)
    server.container = args.container
    server.target_port = args.target_port
    if args.state_file:
        args.state_file.write_text(json.dumps({'pid': os.getpid(), 'port': args.port, 'targetPort': args.target_port, 'container': args.container}))
        args.state_file.chmod(0o600)
    server.serve_forever()
