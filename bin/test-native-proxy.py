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
        self.protocols = []
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
        self.server.protocols.append((self.request_version,
                                      self.connection.selected_alpn_protocol() if isinstance(self.connection, ssl.SSLSocket) else None))

    def respond(self, code=200, value=ARTICLES):
        payload = json.dumps(value).encode() if code == 200 else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self.record()
        if "Content-Length" in self.headers or "Transfer-Encoding" in self.headers:
            self.respond(code=500)
            return
        if self.path == "/ctl/release/latest.json":
            self.respond(value={"version": "0.1.0", "url": "https://dl.zrlog.com/ctl/release/0.1.0/zrlogctl-linux-amd64",
                                "sha256": "a" * 64, "size": 1})
        else:
            self.respond()


class BlockedIPv4Proxy(Origin):
    """Model a policy interceptor on the wrong address of a dual-stack proxy."""
    def do_CONNECT(self):
        self.record()
        self.connection.sendall(b"cross-address-family-policy-test\r\n\r\n")
        self.close_connection = True

    do_GET = do_CONNECT


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
        ca_directory = directory / "ca-certificates"
        ca_directory.mkdir()
        empty_ca_directory = directory / "empty-ca-certificates"
        empty_ca_directory.mkdir()
        ca, ca_key = ca_directory / "runtime-ca.pem", directory / "ca.key"
        cert, key, csr = [directory / name for name in ("cert.pem", "key.pem", "server.csr")]
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-keyout", str(ca_key), "-out", str(ca), "-subj", "/CN=Runtime Proxy Test CA",
                        "-addext", "basicConstraints=critical,CA:TRUE",
                        "-addext", "keyUsage=critical,keyCertSign,cRLSign"], check=True, capture_output=True)
        subprocess.run(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
                        "-out", str(csr), "-subj", "/CN=blog.invalid"], check=True, capture_output=True)

        def sign(name, hosts, expired=False):
            extensions = directory / f"{name}.ext"
            extensions.write_text("[server]\nbasicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\nsubjectAltName=" + hosts + "\n")
            signed = directory / name
            if expired:
                # Use explicit historical dates: some OpenSSL versions reject negative -days.
                (directory / "issued").mkdir()
                (directory / "index.txt").touch()
                (directory / "serial").write_text("01\n")
                config = directory / "ca.cnf"
                config.write_text("[ca]\ndefault_ca=test\n[test]\ncertificate=ca-certificates/runtime-ca.pem\n"
                                  "private_key=ca.key\ndatabase=index.txt\nserial=serial\nnew_certs_dir=issued\n"
                                  "default_md=sha256\npolicy=names\n[names]\ncommonName=supplied\n")
                command = ["openssl", "ca", "-batch", "-notext", "-config", str(config), "-in", str(csr),
                           "-startdate", "20000101000000Z", "-enddate", "20000102000000Z"]
            else:
                command = ["openssl", "x509", "-req", "-in", str(csr), "-CA", str(ca), "-CAkey", str(ca_key),
                           "-CAcreateserial", "-days", "1"]
            subprocess.run([*command, "-out", str(signed), "-extfile", str(extensions), "-extensions", "server"],
                           cwd=directory, check=True, capture_output=True)
            return signed

        sign("cert.pem", "DNS:blog.invalid,DNS:dl.zrlog.com")
        wrong_host = sign("wrong-host.pem", "DNS:other.invalid")
        expired = sign("expired.pem", "DNS:blog.invalid,DNS:dl.zrlog.com", expired=True)
        hosts = directory / "hosts"
        # Deliberately list IPv4 first for the mixed proxy. Only its IPv6 listener
        # can forward requests; its IPv4 listener returns a plaintext policy error.
        hosts.write_text("127.0.0.1 mixed-proxy.invalid\n::1 mixed-proxy.invalid\n"
                         "::1 v6-proxy.invalid\n127.0.0.1 blog.invalid\n")
        environment = {name: value for name, value in os.environ.items()
                       if not name.lower().endswith("_proxy") and not name.startswith("ZRLOG_")
                       and name not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "SSL_CERT_FILE", "SSL_CERT_DIR")}
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
        # Prefer h2 at the server: the CLI must still send HTTP/1.1.
        tls.set_alpn_protocols(["h2", "http/1.1"])
        origin.socket = tls.wrap_socket(origin.socket, server_side=True)
        start(origin)
        ipv4_proxy = start(Server(("127.0.0.1", 0), Proxy))
        ipv6_proxy = start(IPv6Server(("::1", 0), Proxy))
        blocked_ipv4 = start(Server(("127.0.0.1", ipv6_proxy.server_port), BlockedIPv4Proxy))
        ipv4_proxy.origin_port = ipv6_proxy.origin_port = origin.server_port
        site = f"https://blog.invalid:{origin.server_port}/sub"

        def run(proxy_url, secure=True, expected=0, trust_cert=True, command="article", debug=False, ca_bundle=False):
            properties = [f"-Djdk.net.hosts.file={hosts}"]
            # Set all variants so an inherited casing preference cannot affect the test.
            env = environment | {name: proxy_url for name in (
                "https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY", "all_proxy", "ALL_PROXY")}
            env.update(no_proxy="", NO_PROXY="")
            env["SSL_CERT_DIR"] = str(ca_directory if trust_cert and not ca_bundle else empty_ca_directory)
            if trust_cert and ca_bundle:
                env["SSL_CERT_FILE"] = str(ca)
            if debug:
                env["ZRLOG_PROXY_DEBUG"] = "1"
            args = ["--timeout", "5", "--site", site if secure else "http://127.0.0.1:1/sub",
                    "--token", "test-token", "article", "list"] if command == "article" else ["update", "check"]
            result = subprocess.run([binary, *properties, *args],
                                    env=env, cwd=temporary, capture_output=True, text=True, timeout=15)
            assert result.returncode == expected, (result.returncode, result.stdout, result.stderr)
            if not debug:
                assert "[proxy-select]" not in result.stderr, result.stderr
            return result

        for host, proxy in (("127.0.0.1", ipv4_proxy), ("[::1]", ipv6_proxy),
                            ("v6-proxy.invalid", ipv6_proxy), ("mixed-proxy.invalid", ipv6_proxy)):
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
                    version, alpn = origin.protocols[-1]
                    assert version == "HTTP/1.1" and alpn != "h2", origin.protocols[-1]
                else:
                    assert (method, path) == ("GET", "http://127.0.0.1:1" + ARTICLE_PATH)
                assert headers.get("X-ZrLog-Admin-Token") == "test-token"
                print(f"PASS {host}: {'HTTPS CONNECT to IPv4 origin' if secure else 'HTTP forwarding'}, first-request authentication")

        # This runs with the mixed hostname after the HTTP/HTTPS success checks,
        # so the pre-fix binary first reproduces the real wrong-address failure.
        for command in ("article", "update"):
            result = run(proxy_url, command=command, debug=True)
            events = [json.loads(line.removeprefix("[proxy-select] ")) for line in result.stderr.splitlines()
                      if line.startswith("[proxy-select] ")]
            assert len(events) == 1, result.stderr
            selected = events[0]["proxies"]
            assert len(selected) == 1 and selected[0]["type"] == "HTTP", events
            assert not selected[0]["unresolved"] and ":" in selected[0]["address"], events
            assert selected[0]["port"] == ipv6_proxy.server_port, events
            assert all(secret not in result.stderr for secret in (USERINFO, PROXY_AUTH, "p:a@ss%+word", "test-token"))
            target = "dl.zrlog.com:443" if command == "update" else f"blog.invalid:{origin.server_port}"
            assert ipv6_proxy.requests[-1][0:2] == ("CONNECT", target)
            assert not blocked_ipv4.requests, "The IPv4 policy interceptor must never be contacted"
            assert origin.protocols[-1][0] == "HTTP/1.1" and origin.protocols[-1][1] != "h2"
            print(f"PASS mixed proxy {command}: actual selector returns IPv6; debug output omits credentials")

        run(proxy_url, ca_bundle=True)
        print("PASS runtime PEM CA directory/bundle and HTTP/1.1 despite server offering h2 first")

        before_origin = len(origin.requests)
        run(proxy_url, expected=5, trust_cert=False)
        assert len(origin.requests) == before_origin, "TLS certificate validation must remain enabled"
        print("PASS rejects an untrusted origin certificate through the IPv6 proxy")

        for invalid, reason in ((wrong_host, "wrong hostname"), (expired, "expired certificate")):
            tls.load_cert_chain(invalid, key)
            result = run(proxy_url, expected=5)
            assert "SSLHandshakeException" in result.stderr or "certificate" in result.stderr.lower(), result.stderr
            assert len(origin.requests) == before_origin, "PEM trust must not disable hostname or validity checks"
            print(f"PASS rejects {reason} even when its issuing CA is trusted")
        tls.load_cert_chain(cert, key)

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
