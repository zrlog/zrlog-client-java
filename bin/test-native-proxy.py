#!/usr/bin/env python3
"""Exercise the native CLI against local IPv4/IPv6 proxies and an IPv4 TLS origin."""

import base64
import contextlib
import http.server
import json
import os
from pathlib import Path
import select
import socket
import ssl
import subprocess
import sys
import tempfile
import threading


ARTICLES = {"error": 0, "data": {"page": 1, "size": 100, "totalElements": 0, "rows": []}}
ARTICLE_PATH = "/sub/api/admin/article?page=1&size=100&sort=id%2Cdesc"
USERINFO = "u%40ser+name:p%3Aa%40ss%25+word"
PROXY_AUTH = "Basic " + base64.b64encode(b"u@ser+name:p:a@ss%+word").decode()


class Server(http.server.ThreadingHTTPServer):
    def __init__(self, address, handler):
        self.requests = []
        super().__init__(address, handler)


class IPv6Server(Server):
    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        super().server_bind()


class Origin(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def record(self):
        self.server.requests.append((self.command, self.path, self.headers))

    def respond(self, code=200):
        payload = json.dumps(ARTICLES).encode() if code == 200 else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self.record()
        self.respond()


class Proxy(Origin):
    def authenticated(self):
        self.record()
        if self.headers.get("Proxy-Authorization") == PROXY_AUTH:
            return True
        # Intentionally no 407 challenge: authentication must be on the first request.
        self.respond(403)
        return False

    def do_GET(self):
        if self.authenticated():
            self.respond()

    def do_CONNECT(self):
        if not self.authenticated():
            return
        with socket.create_connection(("127.0.0.1", self.server.origin_port), timeout=5) as upstream:
            upstream.settimeout(None)
            self.send_response(200, "Connection Established")
            self.end_headers()
            self.wfile.flush()
            peers = [self.connection, upstream]
            try:
                while True:
                    readable, _, _ = select.select(peers, [], [], 10)
                    if not readable:
                        return
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        (upstream if source is self.connection else self.connection).sendall(data)
            except (ConnectionError, TimeoutError):
                # The client may close the tunnel after rejecting the test certificate.
                return


def main(binary):
    binary = str(Path(binary).resolve())
    with tempfile.TemporaryDirectory(prefix="zrlogctl-native-proxy-") as temporary, contextlib.ExitStack() as cleanup:
        directory = Path(temporary)
        cert, key, trust = [directory / name for name in ("cert.pem", "key.pem", "trust.p12")]
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-keyout", str(key), "-out", str(cert), "-subj", "/CN=blog.invalid",
                        "-addext", "subjectAltName=DNS:blog.invalid"], check=True, capture_output=True)
        keytool = str(Path(os.environ["JAVA_HOME"]) / "bin/keytool") if os.environ.get("JAVA_HOME") else "keytool"
        subprocess.run([keytool, "-importcert", "-noprompt", "-alias", "test-origin", "-file", str(cert),
                        "-keystore", str(trust), "-storetype", "PKCS12", "-storepass", "local-test"],
                       check=True, capture_output=True)
        hosts = directory / "hosts"
        # Only an IPv6 address exists for the proxy; the origin has only IPv4.
        hosts.write_text("::1 v6-proxy.invalid\n127.0.0.1 blog.invalid\n")
        environment = {name: value for name, value in os.environ.items()
                       if not name.lower().endswith("_proxy") and not name.startswith("ZRLOG_")
                       and name not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
        environment["XDG_CONFIG_HOME"] = temporary

        def start(server):
            cleanup.callback(server.server_close)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            cleanup.callback(thread.join, 5)
            cleanup.callback(server.shutdown)
            return server

        origin = Server(("127.0.0.1", 0), Origin)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
        origin.socket = tls.wrap_socket(origin.socket, server_side=True)
        start(origin)
        ipv4_proxy = start(Server(("127.0.0.1", 0), Proxy))
        ipv6_proxy = start(IPv6Server(("::1", 0), Proxy))
        ipv4_proxy.origin_port = ipv6_proxy.origin_port = origin.server_port
        site = f"https://blog.invalid:{origin.server_port}/sub"

        def run(proxy_url, secure=True, expected=0, trust_cert=True):
            properties = [f"-Djdk.net.hosts.file={hosts}"]
            if trust_cert:
                properties += [f"-Djavax.net.ssl.trustStore={trust}", "-Djavax.net.ssl.trustStorePassword=local-test"]
            # Set all variants so an inherited casing preference cannot affect the test.
            env = environment | {name: proxy_url for name in (
                "https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY", "all_proxy", "ALL_PROXY")}
            env.update(no_proxy="", NO_PROXY="")
            result = subprocess.run([binary, *properties, "--timeout", "5", "--site",
                                     site if secure else "http://127.0.0.1:1/sub",
                                     "--token", "test-token", "article", "list"],
                                    env=env, cwd=temporary, capture_output=True, text=True, timeout=15)
            assert result.returncode == expected, (result.returncode, result.stdout, result.stderr)
            return result

        for host, proxy in (("127.0.0.1", ipv4_proxy), ("[::1]", ipv6_proxy), ("v6-proxy.invalid", ipv6_proxy)):
            proxy_url = f"http://{USERINFO}@{host}:{proxy.server_port}"
            for secure in (False, True):
                before_proxy, before_origin = len(proxy.requests), len(origin.requests)
                run(proxy_url, secure=secure)
                assert len(proxy.requests) == before_proxy + 1, "Expected exactly one authenticated proxy request"
                method, path, headers = proxy.requests[-1]
                assert headers.get("Proxy-Authorization") == PROXY_AUTH
                if secure:
                    assert (method, path) == ("CONNECT", f"blog.invalid:{origin.server_port}")
                    assert headers.get("Authorization") is None
                    assert headers.get("X-ZrLog-Admin-Token") is None
                    assert len(origin.requests) == before_origin + 1
                    method, path, headers = origin.requests[-1]
                    assert (method, path) == ("GET", ARTICLE_PATH)
                    assert headers.get("Proxy-Authorization") is None
                else:
                    assert (method, path) == ("GET", "http://127.0.0.1:1" + ARTICLE_PATH)
                assert headers.get("X-ZrLog-Admin-Token") == "test-token"
                print(f"PASS {host}: {'HTTPS CONNECT to IPv4 origin' if secure else 'HTTP forwarding'}, first-request authentication")

        before_origin = len(origin.requests)
        run(proxy_url, expected=5, trust_cert=False)
        assert len(origin.requests) == before_origin, "TLS certificate validation must remain enabled"
        print("PASS rejects an untrusted origin certificate through the IPv6 proxy")

        for family, address, host in ((socket.AF_INET, "127.0.0.1", "127.0.0.1"), (socket.AF_INET6, "::1", "[::1]")):
            # Reserve the port without listening so no other process can take it.
            with socket.socket(family) as unused:
                unused.bind((address, 0))
                result = run(f"http://user:pass@{host}:{unused.getsockname()[1]}", expected=5)
            assert "ConnectException" in result.stderr or "connection refused" in result.stderr.lower(), result.stderr
            assert len(origin.requests) == before_origin, "An unreachable proxy must not fall back to the reachable origin"
            print(f"PASS {host}: refused proxy connection without direct fallback")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(f"usage: {sys.argv[0]} NATIVE_BINARY")
    main(sys.argv[1])
