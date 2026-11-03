#!/usr/bin/env python3
"""只在 DS 工作节点回环开放的独立 HTTP/FTP 验收夹具，不接受外部地址或已有目录。"""
import argparse
import csv
import hashlib
import http.server
import json
import logging
from pathlib import Path
import secrets
import sys
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--http-port', type=int, default=19087)
    parser.add_argument('--ftp-port', type=int, default=19088)
    parser.add_argument('--ftp-library', default='/opt/datahub-fixtures/ftp-pyftpdlib-2.2.0')
    parser.add_argument('--resume', action='store_true', help='复用本夹具已有秘密和公开nonce，不覆盖已有目标文件')
    args = parser.parse_args()
    secret_root = args.root / 'secrets'; home = args.root / 'ftp'
    if args.resume:
        for path in [args.root, secret_root, home]:
            if not path.is_dir() or path.is_symlink(): raise ValueError('已有夹具根目录无效')
        for path in [secret_root / 'apiAccess', secret_root / 'ftpAccess', args.root / 'public.json']:
            if not path.is_file() or path.is_symlink(): raise ValueError('已有夹具身份文件缺失')
        public = json.loads((args.root / 'public.json').read_text())
        if public['httpPort'] != args.http_port or public['ftpPort'] != args.ftp_port: raise ValueError('已有夹具端口合同不同')
        api_token = (secret_root / 'apiAccess').read_text()
        ftp_password = json.loads((secret_root / 'ftpAccess').read_text())['password']; nonce = public['nonce']
    else:
        args.root.mkdir(mode=0o700); secret_root.mkdir(mode=0o700); home.mkdir(mode=0o700)
        for name in ['http', 'crypto', 'token']: (home / name).mkdir()
        api_token = 'Bearer ' + secrets.token_hex(24); ftp_password = secrets.token_hex(24); nonce = secrets.token_hex(24)
        for name, value in {'apiAccess': api_token, 'ftpAccess': json.dumps({'username': 'component_fixture', 'password': ftp_password}),
                            'cryptoAccess': 'A1' + secrets.token_hex(7)}.items():
            path = secret_root / name; path.write_text(value); path.chmod(0o600)
        (args.root / 'public.json').write_text(json.dumps({'nonce': nonce, 'httpPort': args.http_port, 'ftpPort': args.ftp_port}))
        with (home / 'input.csv').open('w', newline='') as stream:
            writer = csv.writer(stream); writer.writerow(['id', 'label', 'amount'])
            writer.writerows([[9007199254740993, '河道😀,\nO\'Reilly"', '123456.120000000000000001'], [9007199254740994, '', '\\N']])
    token = 'Bearer ' + hashlib.sha256(nonce.encode()).hexdigest()
    rows = [{'id': 9007199254740993, 'label': '河道😀,\nO\'Reilly"', 'amount': '123456.120000000000000001'},
            {'id': 9007199254740994, 'label': '', 'amount': None}]

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != '/login':
                self.send_response(404); self.end_headers(); return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if size < 1 or size > 4096: raise ValueError()
                request = json.loads(self.rfile.read(size))
                challenge = request.get('nonce')
                if set(request) != {'nonce'} or not isinstance(challenge, str) or not secrets.compare_digest(challenge, nonce): raise ValueError()
            except (ValueError, TypeError):
                self.send_response(400); self.end_headers(); return
            body = json.dumps({'token': token.removeprefix('Bearer ')}).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json;charset=UTF-8')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)

        def do_GET(self):
            if self.path not in ['/rows', '/token-rows']:
                self.send_response(404); self.end_headers(); return
            expected = token if self.path == '/token-rows' else api_token
            if not secrets.compare_digest(self.headers.get('Authorization', ''), expected):
                self.send_response(401); self.end_headers(); return
            body = json.dumps({'data': {'rows': rows}}, ensure_ascii=False).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json;charset=UTF-8')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)

        def log_message(self, *unused):
            pass

    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.http_port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    sys.path.insert(0, args.ftp_library)
    from pyftpdlib.authorizers import DummyAuthorizer
    from pyftpdlib.handlers import FTPHandler
    from pyftpdlib.servers import FTPServer
    logging.disable(logging.CRITICAL)
    authorizer = DummyAuthorizer(); authorizer.add_user('component_fixture', ftp_password, str(home), perm='elradfmwMT')

    class FtpHandler(FTPHandler):
        pass

    FtpHandler.authorizer = authorizer
    try:
        FTPServer(('127.0.0.1', args.ftp_port), FtpHandler).serve_forever()
    finally:
        server.shutdown(); server.server_close()


if __name__ == '__main__':
    main()
