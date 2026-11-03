import os

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer


authorizer = DummyAuthorizer()
authorizer.add_user(
    os.environ["FTP_USER"], os.environ["FTP_PASSWORD"], "/data", perm="elradfmwMT"
)
FTPHandler.authorizer = authorizer
FTPHandler.passive_ports = range(21000, 21010)
FTPHandler.masquerade_address = "127.0.0.1"
FTPServer(("0.0.0.0", 2121), FTPHandler).serve_forever()
