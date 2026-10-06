#!/usr/bin/env python3
"""Exercise runtime OpenAPI parsing and requests in the actual native executable."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    args = parser.parse_args()
    binary = args.binary.resolve()
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def handle_api(self):
            # Reproduce the deployed Worker rejecting GET requests with Content-Length: 0.
            if self.command == "GET" and ("Content-Length" in self.headers or "Transfer-Encoding" in self.headers):
                self.send_error(500, "Unexpected request body framing on GET")
                return
            # Native request bodies have a known length for the cases below.
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            requests.append((self.command, self.path, dict(self.headers), body))
            self.send_response(200)
            if self.path.endswith("/stream"):
                data = b"event: progress\ndata: native\n\nevent: done\ndata: {}\n\n"
                content_type = "text/event-stream"
            elif self.path.endswith("/incomplete"):
                data = b"event: progress\ndata: native\n\n"
                content_type = "text/event-stream"
            else:
                payload = {"error": 0, "native": True}
                if self.path.startswith("/sub/api/admin/upload"):
                    payload["data"] = {"url": "/sub/attached/image.png"}
                elif self.path.startswith("/sub/api/admin/template/upload"):
                    payload["data"] = {"shortTemplate": "native-theme", "name": "Native", "overwritten": False}
                elif self.path == "/sub/api/admin/article-type":
                    payload["data"] = {"rows": []}
                data = json.dumps(payload).encode()
                content_type = "application/json"
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        do_PUT = handle_api
        do_POST = handle_api
        do_GET = handle_api

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="zrlogctl-openapi-") as temporary:
            directory = Path(temporary)
            environment = {key: value for key, value in os.environ.items()
                           if key.lower() not in {"http_proxy", "https_proxy", "all_proxy", "no_proxy"}
                           and key not in {"ZRLOG_ACCESS_TOKEN", "ZRLOG_ADMIN_TOKEN", "ZRLOG_SITE_URL", "SSL_CERT_FILE", "SSL_CERT_DIR"}}
            environment["XDG_CONFIG_HOME"] = str(directory / "config")
            site = f"http://127.0.0.1:{server.server_port}/sub"

            def run(*arguments, status=0):
                result = subprocess.run([str(binary), "--output", "json", "--site", site, *arguments],
                                        cwd=directory, env=environment, text=True, capture_output=True, timeout=20)
                assert result.returncode == status, (arguments, result.returncode, result.stderr)
                return result

            sources = json.loads(run("api", "sources").stdout)
            assert {source["id"] for source in sources} == {"admin-web", "blog-web"}
            assert len(json.loads(run("api", "list").stdout)) == 10
            assert len(json.loads(run("api", "list", "--source", "blog-web").stdout)) == 5
            article = {"title": "Native draft", "typeId": 1, "canComment": True, "privacy": False,
                       "recommended": False, "rubbish": True, "markdown": None}
            (directory / "article.json").write_text(json.dumps(article))
            run("api", "call", "createArticle", "--body", "@article.json", "--dry-run")
            article["unexpected"] = 1
            run("api", "call", "createArticle", "--body", json.dumps(article), "--dry-run", status=3)

            listed = json.loads(run("api", "call", "listArticles", "--query", "page=1", "--query", "size=100",
                                    "--query", "sort=id,desc", "--query", "status=", "--dry-run").stdout)
            assert listed["path"] == "/api/admin/article?page=1&size=100&sort=id%2Cdesc&status="
            run("api", "call", "getArticle", "--query", "id=42", "--dry-run")
            run("api", "call", "getArticle", "--dry-run", status=3)
            run("api", "call", "listCategories", "--dry-run")
            category = {"typeName": "Native category", "alias": "native", "remark": None}
            run("api", "call", "createCategory", "--body", json.dumps(category), "--dry-run")
            run("api", "call", "updateCategory", "--body", json.dumps(category), "--dry-run", status=3)
            category["id"] = 1
            run("api", "call", "updateCategory", "--body", json.dumps(category), "--accept", "text/event-stream", "--dry-run")

            # This operation did not exist when the binary was compiled.
            spec = {"openapi": "3.1.0", "info": {"title": "Runtime Native Test", "version": "1"},
                    "components": {"securitySchemes": {"token": {"type": "http", "scheme": "bearer"}}},
                    "paths": {
                        "/dynamic/{id}": {"put": {
                            "operationId": "runtimeOnly", "security": [{"token": []}],
                            "parameters": [
                                {"name": "id", "in": "path", "required": True, "schema": {"type": "string"}},
                                {"name": "tags", "in": "query", "schema": {"type": "array", "items": {"type": "string"}}}],
                            "requestBody": {"required": True, "content": {"application/json": {
                                "schema": {"type": "object", "required": ["enabled"], "properties": {"enabled": {"type": "boolean"}}}}}},
                            "responses": {"200": {"description": "ok", "content": {"application/json": {}}}}}},
                        "/public": {"get": {"operationId": "public", "responses": {"200": {"description": "ok", "content": {"application/json": {}}}}}},
                    }}
            for operation in ("stream", "incomplete"):
                spec["paths"][f"/{operation}"] = {"post": {"operationId": operation, "responses": {
                    "200": {"description": "stream", "content": {"text/event-stream": {
                        "x-zrlog-stream": {"completionEvent": "done", "errorEvents": ["failed"]}, "schema": {"type": "string"}}}}}}}
            (directory / "api.json").write_text(json.dumps(spec))
            token = "zrpat_" + "a" * 43
            run("--token", token, "api", "--spec", "api.json", "call", "runtimeOnly", "--path", "id=a/b",
                "--query", 'tags=["a b","x+y"]', "--body", '{"enabled":false}')
            method, path, headers, body = requests[-1]
            assert method == "PUT" and path == "/sub/dynamic/a%2Fb?tags=a%20b&tags=x%2By", requests[-1]
            assert headers["Authorization"] == "Bearer " + token
            assert json.loads(body) == {"enabled": False}
            run("--token", token, "api", "--spec", "api.json", "call", "public")
            assert "Authorization" not in requests[-1][2]

            (directory / "image.png").write_bytes(b"native image bytes")
            run("--token", token, "api", "call", "uploadAttachment", "--file", "imgFile=image.png")
            assert b'name="imgFile"; filename="image.png"' in requests[-1][3]
            assert b"native image bytes" in requests[-1][3]
            complete = run("api", "--spec", "api.json", "call", "stream")
            assert json.loads(complete.stdout)["completionEvent"] == "done"
            assert json.loads(complete.stderr.splitlines()[0])["data"] == "native"
            run("api", "--spec", "api.json", "call", "incomplete", status=5)
            assert len(requests) == 5, requests
            assert json.loads(run("--token", token, "category", "list").stdout) == []
            media = run("--token", token, "media", "upload", "image.png")
            assert json.loads(media.stdout)["url"] == "/sub/attached/image.png"
            assert b"Content-Type: image/png" in requests[-1][3]
            theme = directory / "native-theme"
            theme.mkdir()
            (theme / "index.ftl").write_text("Native theme")
            run("--token", token, "theme", "upload", str(theme))
            assert b'filename="native-theme.zip"' in requests[-1][3]
            assert b"Content-Type: application/zip" in requests[-1][3]
            assert len(requests) == 8, requests
            print("Native OpenAPI checks passed: catalog, runtime operations, schemas, auth, SSE, category/media/theme commands")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
