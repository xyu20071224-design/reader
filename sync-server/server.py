#!/usr/bin/env python3
"""LinguaReader 同步服务端：REST + JSON，Python 标准库实现，零第三方依赖。

协议与数据模型见 全平台与同步(新目标)/阶段2-同步协议与部署-提案.md。
用法：
    python3 server.py create-user <username> [--password X]
    python3 server.py serve
环境变量见同目录 README.md。
"""
from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import json
import os
import secrets
import socketserver
import sqlite3
import ssl
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTOCOL_VERSION = 1
PBKDF2_ITERATIONS = 210_000
TOKEN_BYTES = 32
READ_CHUNK = 256 * 1024
MAX_JSON_BYTES = 8 * 1024 * 1024
COLLECTIONS = ("progress", "vocabulary", "glossary", "preference")

SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users (
    id          TEXT PRIMARY KEY,
    username    TEXT NOT NULL UNIQUE,
    pw_salt     BLOB NOT NULL,
    pw_hash     BLOB NOT NULL,
    seq_counter INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tokens (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS records (
    user_id    TEXT NOT NULL,
    collection TEXT NOT NULL,
    id         TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER NOT NULL DEFAULT 0,
    payload    TEXT NOT NULL,
    server_seq INTEGER NOT NULL,
    PRIMARY KEY (user_id, collection, id)
);
CREATE INDEX IF NOT EXISTS idx_records_seq ON records(user_id, server_seq);
CREATE TABLE IF NOT EXISTS blobs (
    user_id    TEXT NOT NULL,
    blob_id    TEXT NOT NULL,
    size       INTEGER NOT NULL,
    sha256     TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, blob_id)
);
"""


def now_ms() -> int:
    return int(time.time() * 1000)


def hash_password(password: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ITERATIONS)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class SyncError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


class Store:
    """SQLite 存储 + 全部业务规则。所有公开方法自带锁，可被多线程 handler 调用。"""

    def __init__(self, db_path: str, blob_dir: str, quota_mb: int = 2048, token_ttl_days: int = 30):
        self.db_path = db_path
        self.blob_dir = blob_dir
        self.quota_bytes = max(0, quota_mb) * 1024 * 1024
        self.token_ttl_ms = token_ttl_days * 24 * 3600 * 1000
        os.makedirs(os.path.dirname(os.path.abspath(db_path)) or ".", exist_ok=True)
        os.makedirs(blob_dir, exist_ok=True)
        self._lock = threading.RLock()
        self.db = sqlite3.connect(db_path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        with self._lock:
            self.db.executescript(SCHEMA)
            self.db.commit()

    # ---------- 账号 ----------

    def create_user(self, username: str, password: str) -> str:
        username = (username or "").strip()
        if not username:
            raise SyncError(400, "invalid_username", "username is required")
        if len(password or "") < 8:
            raise SyncError(400, "weak_password", "password must be at least 8 characters")
        with self._lock:
            row = self.db.execute("SELECT id FROM users WHERE username = ?", (username,)).fetchone()
            if row is not None:
                raise SyncError(409, "username_taken", "username already exists")
            user_id = secrets.token_hex(16)
            salt = secrets.token_bytes(16)
            self.db.execute(
                "INSERT INTO users (id, username, pw_salt, pw_hash, seq_counter, created_at) VALUES (?, ?, ?, ?, 0, ?)",
                (user_id, username, salt, hash_password(password, salt), now_ms()),
            )
            self.db.commit()
            return user_id

    def login(self, username: str, password: str) -> tuple[str, int]:
        with self._lock:
            row = self.db.execute(
                "SELECT id, pw_salt, pw_hash FROM users WHERE username = ?", ((username or "").strip(),)
            ).fetchone()
            if row is None:
                raise SyncError(401, "bad_credentials", "invalid username or password")
            candidate = hash_password(password or "", bytes(row["pw_salt"]))
            if not secrets.compare_digest(candidate, bytes(row["pw_hash"])):
                raise SyncError(401, "bad_credentials", "invalid username or password")
            token = secrets.token_urlsafe(TOKEN_BYTES)
            expires_at = now_ms() + self.token_ttl_ms
            self.db.execute(
                "INSERT INTO tokens (token_hash, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
                (hash_token(token), row["id"], expires_at, now_ms()),
            )
            self.db.commit()
            return token, expires_at

    def user_for_token(self, token: str) -> str:
        if not token:
            raise SyncError(401, "missing_token", "Authorization: Bearer <token> is required")
        with self._lock:
            row = self.db.execute(
                "SELECT user_id, expires_at FROM tokens WHERE token_hash = ?", (hash_token(token),)
            ).fetchone()
            if row is None:
                raise SyncError(401, "invalid_token", "token is not valid")
            if int(row["expires_at"]) < now_ms():
                self.db.execute("DELETE FROM tokens WHERE token_hash = ?", (hash_token(token),))
                self.db.commit()
                raise SyncError(401, "expired_token", "token has expired")
            return row["user_id"]

    def revoke_token(self, token: str) -> None:
        with self._lock:
            self.db.execute("DELETE FROM tokens WHERE token_hash = ?", (hash_token(token),))
            self.db.commit()

    # ---------- 变更流 ----------

    def _record(self, row: sqlite3.Row) -> dict:
        return {
            "collection": row["collection"],
            "id": row["id"],
            "updatedAt": row["updated_at"],
            "deletedAt": row["deleted_at"],
            "payload": json.loads(row["payload"]),
            "serverSeq": row["server_seq"],
        }

    def _next_seq(self, user_id: str) -> int:
        self.db.execute("UPDATE users SET seq_counter = seq_counter + 1 WHERE id = ?", (user_id,))
        row = self.db.execute("SELECT seq_counter FROM users WHERE id = ?", (user_id,)).fetchone()
        return int(row["seq_counter"])

    def changes_since(self, user_id: str, since: int, limit: int = 500) -> dict:
        limit = max(1, min(limit, 2000))
        with self._lock:
            rows = self.db.execute(
                "SELECT * FROM records WHERE user_id = ? AND server_seq > ? ORDER BY server_seq LIMIT ?",
                (user_id, since, limit + 1),
            ).fetchall()
            has_more = len(rows) > limit
            rows = rows[:limit]
            next_seq = int(rows[-1]["server_seq"]) if rows else since
            return {
                "records": [self._record(r) for r in rows],
                "nextSeq": next_seq,
                "hasMore": has_more,
            }

    def full_state(self, user_id: str) -> dict:
        with self._lock:
            rows = self.db.execute(
                "SELECT * FROM records WHERE user_id = ? ORDER BY server_seq", (user_id,)
            ).fetchall()
            top = self.db.execute("SELECT seq_counter FROM users WHERE id = ?", (user_id,)).fetchone()
            return {"records": [self._record(r) for r in rows], "nextSeq": int(top["seq_counter"])}

    def apply_changes(self, user_id: str, incoming: list) -> dict:
        applied, conflicts = [], []
        with self._lock:
            for raw in incoming:
                collection = raw.get("collection")
                rid = raw.get("id")
                if collection not in COLLECTIONS:
                    raise SyncError(400, "bad_collection", "unsupported collection: %r" % (collection,))
                if not isinstance(rid, str) or not rid:
                    raise SyncError(400, "bad_id", "record id is required")
                updated_at = int(raw.get("updatedAt") or 0)
                deleted_at = int(raw.get("deletedAt") or 0)
                payload = raw.get("payload")
                if not isinstance(payload, dict):
                    raise SyncError(400, "bad_payload", "payload must be a JSON object")
                existing = self.db.execute(
                    "SELECT * FROM records WHERE user_id = ? AND collection = ? AND id = ?",
                    (user_id, collection, rid),
                ).fetchone()
                if existing is not None and updated_at <= int(existing["updated_at"]):
                    conflicts.append(self._record(existing))
                    continue
                seq = self._next_seq(user_id)
                self.db.execute(
                    "INSERT INTO records (user_id, collection, id, updated_at, deleted_at, payload, server_seq)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?)"
                    " ON CONFLICT(user_id, collection, id) DO UPDATE SET"
                    " updated_at = excluded.updated_at, deleted_at = excluded.deleted_at,"
                    " payload = excluded.payload, server_seq = excluded.server_seq",
                    (user_id, collection, rid, updated_at, deleted_at, json.dumps(payload, ensure_ascii=False), seq),
                )
                applied.append({
                    "collection": collection, "id": rid, "updatedAt": updated_at,
                    "deletedAt": deleted_at, "payload": payload, "serverSeq": seq,
                })
            self.db.commit()
            top = self.db.execute("SELECT seq_counter FROM users WHERE id = ?", (user_id,)).fetchone()
        return {"applied": applied, "conflicts": conflicts, "nextSeq": int(top["seq_counter"])}

    # ---------- 书籍正文（blob） ----------

    def _user_blob_dir(self, user_id: str) -> str:
        path = os.path.join(self.blob_dir, user_id)
        os.makedirs(path, exist_ok=True)
        return path

    def _blob_paths(self, user_id: str, blob_id: str) -> tuple[str, str]:
        if not blob_id or "/" in blob_id or ".." in blob_id:
            raise SyncError(400, "bad_blob_id", "invalid blob id")
        base = os.path.join(self._user_blob_dir(user_id), blob_id)
        return base + ".bin", base + ".part"

    def blob_usage(self, user_id: str) -> int:
        with self._lock:
            row = self.db.execute(
                "SELECT COALESCE(SUM(size), 0) AS total FROM blobs WHERE user_id = ?", (user_id,)
            ).fetchone()
            return int(row["total"])

    def list_blobs(self, user_id: str) -> list:
        with self._lock:
            rows = self.db.execute(
                "SELECT blob_id, size, sha256 FROM blobs WHERE user_id = ? ORDER BY blob_id", (user_id,)
            ).fetchall()
            return [{"bookId": r["blob_id"], "size": int(r["size"]), "sha256": r["sha256"]} for r in rows]

    def blob_status(self, user_id: str, blob_id: str) -> dict:
        final, part = self._blob_paths(user_id, blob_id)
        with self._lock:
            if os.path.isfile(final):
                return {"complete": True, "uploaded": os.path.getsize(final)}
            if os.path.isfile(part):
                return {"complete": False, "uploaded": os.path.getsize(part)}
            return {"complete": False, "uploaded": 0}

    def blob_append(self, user_id: str, blob_id: str, offset: int, data: bytes, total: int, sha256: str) -> dict:
        final, part = self._blob_paths(user_id, blob_id)
        with self._lock:
            if os.path.isfile(final):
                return {"uploaded": os.path.getsize(final), "size": os.path.getsize(final), "complete": True}
            current = os.path.getsize(part) if os.path.isfile(part) else 0
            if offset != current:
                raise SyncError(409, "offset_mismatch", "expected offset %d, got %d" % (current, offset))
            projected = current + len(data)
            if total and projected > total:
                raise SyncError(400, "overflow", "chunk exceeds declared total")
            if self.quota_bytes and self.blob_usage(user_id) + (total or projected) > self.quota_bytes:
                raise SyncError(413, "quota_exceeded", "blob quota exceeded")
            with open(part, "ab") as handle:
                handle.write(data)
            size = os.path.getsize(part)
            complete = bool(total) and size == total
            if complete:
                digest = hashlib.sha256()
                with open(part, "rb") as handle:
                    for chunk in iter(lambda: handle.read(READ_CHUNK), b""):
                        digest.update(chunk)
                actual = digest.hexdigest()
                if sha256 and sha256.lower() != actual:
                    os.remove(part)
                    raise SyncError(400, "checksum_mismatch", "sha256 mismatch")
                os.replace(part, final)
                self.db.execute(
                    "INSERT INTO blobs (user_id, blob_id, size, sha256, created_at) VALUES (?, ?, ?, ?, ?)"
                    " ON CONFLICT(user_id, blob_id) DO UPDATE SET size = excluded.size,"
                    " sha256 = excluded.sha256, created_at = excluded.created_at",
                    (user_id, blob_id, size, actual, now_ms()),
                )
                self.db.commit()
            return {"uploaded": size, "size": total or size, "complete": complete}

    def blob_read(self, user_id: str, blob_id: str, start: int, end: int) -> tuple[bytes, int]:
        final, _ = self._blob_paths(user_id, blob_id)
        if not os.path.isfile(final):
            raise SyncError(404, "blob_not_found", "blob not found")
        size = os.path.getsize(final)
        start = max(0, start)
        end = size - 1 if end < 0 else min(end, size - 1)
        if start > end:
            raise SyncError(416, "range_not_satisfiable", "invalid range")
        with open(final, "rb") as handle:
            handle.seek(start)
            return handle.read(end - start + 1), size

    def blob_delete(self, user_id: str, blob_id: str) -> None:
        final, part = self._blob_paths(user_id, blob_id)
        with self._lock:
            for path in (final, part):
                if os.path.isfile(path):
                    os.remove(path)
            self.db.execute("DELETE FROM blobs WHERE user_id = ? AND blob_id = ?", (user_id, blob_id))
            self.db.commit()


class Config:
    def __init__(self, env=None):
        env = env if env is not None else os.environ
        self.db_path = env.get("LR_SYNC_DB", "sync.db")
        self.blob_dir = env.get("LR_SYNC_BLOB_DIR", "blobs")
        self.host = env.get("LR_SYNC_HOST", "0.0.0.0")
        self.port = int(env.get("LR_SYNC_PORT", "8787"))
        self.quota_mb = int(env.get("LR_SYNC_BLOB_QUOTA_MB", "2048"))
        self.token_ttl_days = int(env.get("LR_SYNC_TOKEN_TTL_DAYS", "30"))
        self.allow_register = env.get("LR_SYNC_ALLOW_REGISTER", "0") == "1"
        self.tls_cert = env.get("LR_SYNC_TLS_CERT", "")
        self.tls_key = env.get("LR_SYNC_TLS_KEY", "")


class Handler(BaseHTTPRequestHandler):
    server_version = "LinguaReaderSync/" + str(PROTOCOL_VERSION)
    store: Store = None  # type: ignore
    config: Config = None  # type: ignore

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s" % (self.address_string(), fmt % args) + os.linesep)

    # ---------- helpers ----------

    def _send_json(self, status: int, body: dict, extra_headers: dict | None = None):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _error(self, error: SyncError):
        self._send_json(error.status, {"error": {"code": error.code, "message": error.message}})

    def _read_body(self, limit: int | None = None) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        if length < 0:
            raise SyncError(400, "bad_length", "invalid Content-Length")
        if limit is None:
            # 分片上限 = 配额 + 1 MiB（留出元数据余量）；未设配额时给 64 MiB。
            limit = (self.config.quota_mb + 1) * 1024 * 1024 if self.config.quota_mb else 64 * 1024 * 1024
        if length > limit:
            raise SyncError(413, "payload_too_large", "request body too large")
        return self.rfile.read(length) if length else b""

    def _read_json(self) -> dict:
        raw = self._read_body(MAX_JSON_BYTES)
        if not raw:
            return {}
        try:
            value = json.loads(raw.decode("utf-8"))
        except ValueError:
            raise SyncError(400, "bad_json", "request body is not valid JSON")
        if not isinstance(value, dict):
            raise SyncError(400, "bad_json", "request body must be a JSON object")
        return value

    def _token(self) -> str:
        header = self.headers.get("Authorization") or ""
        if header.startswith("Bearer "):
            return header[7:].strip()
        return ""

    def _user(self) -> tuple[str, str]:
        token = self._token()
        return self.store.user_for_token(token), token

    # ---------- verbs ----------

    def do_GET(self):
        self._dispatch("GET")

    def do_HEAD(self):
        self._dispatch("HEAD")

    def do_POST(self):
        self._dispatch("POST")

    def do_PUT(self):
        self._dispatch("PUT")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def _dispatch(self, method: str):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = urllib.parse.parse_qs(parsed.query)
        try:
            if path == "/api/v1/health" and method in ("GET", "HEAD"):
                return self._send_json(200, {"status": "ok", "version": PROTOCOL_VERSION})
            if path == "/api/v1/auth/register" and method == "POST":
                return self._register()
            if path == "/api/v1/auth/login" and method == "POST":
                return self._login()
            if path == "/api/v1/auth/logout" and method == "POST":
                return self._logout()
            if path == "/api/v1/changes" and method == "GET":
                return self._changes_get(query)
            if path == "/api/v1/changes" and method == "POST":
                return self._changes_post()
            if path == "/api/v1/state" and method == "GET":
                return self._state_get()
            if path == "/api/v1/books" and method == "GET":
                return self._books_get()
            if path.startswith("/api/v1/blobs/"):
                blob_id = urllib.parse.unquote(path[len("/api/v1/blobs/"):])
                if method in ("HEAD", "GET"):
                    return self._blob_get(blob_id, method)
                if method == "PUT":
                    return self._blob_put(blob_id, query)
                if method == "DELETE":
                    return self._blob_delete(blob_id)
            raise SyncError(404, "not_found", "no such endpoint: %s %s" % (method, path))
        except SyncError as error:
            self._error(error)
        except Exception as error:  # noqa: BLE001 - 兜底为 500，避免连接悬挂
            self._error(SyncError(500, "internal_error", str(error)))

    # ---------- auth ----------

    def _register(self):
        if not self.config.allow_register:
            raise SyncError(403, "registration_closed", "registration is disabled on this server")
        body = self._read_json()
        user_id = self.store.create_user(str(body.get("username") or ""), str(body.get("password") or ""))
        self._send_json(201, {"userId": user_id})

    def _login(self):
        body = self._read_json()
        token, expires_at = self.store.login(str(body.get("username") or ""), str(body.get("password") or ""))
        self._send_json(200, {"token": token, "expiresAt": expires_at})

    def _logout(self):
        _, token = self._user()
        self.store.revoke_token(token)
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ---------- data ----------

    def _changes_get(self, query):
        user_id, _ = self._user()
        since = int((query.get("since") or ["0"])[0])
        limit = int((query.get("limit") or ["500"])[0])
        self._send_json(200, self.store.changes_since(user_id, since, limit))

    def _changes_post(self):
        user_id, _ = self._user()
        body = self._read_json()
        records = body.get("records")
        if not isinstance(records, list):
            raise SyncError(400, "bad_records", "records must be a JSON array")
        self._send_json(200, self.store.apply_changes(user_id, records))

    def _state_get(self):
        user_id, _ = self._user()
        self._send_json(200, self.store.full_state(user_id))

    def _books_get(self):
        user_id, _ = self._user()
        self._send_json(200, {
            "books": self.store.list_blobs(user_id),
            "usedBytes": self.store.blob_usage(user_id),
            "quotaBytes": self.store.quota_bytes,
        })

    # ---------- blobs ----------

    def _blob_get(self, blob_id: str, method: str):
        user_id, _ = self._user()
        status = self.store.blob_status(user_id, blob_id)
        if method == "HEAD":
            self.send_response(200)
            self.send_header("X-Blob-Complete", "1" if status["complete"] else "0")
            self.send_header("X-Blob-Size", str(status["uploaded"]))
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        range_header = self.headers.get("Range") or ""
        start, end = 0, -1
        if range_header.startswith("bytes="):
            spec = range_header[6:].split(",")[0]
            first, _, last = spec.partition("-")
            start = int(first) if first else 0
            end = int(last) if last else -1
        data, size = self.store.blob_read(user_id, blob_id, start, end)
        if range_header:
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, start + len(data) - 1, size))
        else:
            self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _blob_put(self, blob_id: str, query):
        user_id, _ = self._user()
        offset = int((query.get("offset") or ["0"])[0])
        total = int((query.get("total") or ["0"])[0])
        sha256 = (query.get("sha256") or [""])[0]
        data = self._read_body()
        self._send_json(200, self.store.blob_append(user_id, blob_id, offset, data, total, sha256))

    def _blob_delete(self, blob_id: str):
        user_id, _ = self._user()
        self.store.blob_delete(user_id, blob_id)
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()


class SyncHttpServer(ThreadingHTTPServer):
    """覆盖 server_bind。

    ThreadingHTTPServer.server_bind 会调用 socket.getfqdn(host) 做反向解析；
    **macOS 上这一步可能阻塞十几秒到几十秒**，导致服务端在客户端的健康检查窗口内
    根本没有开始 accept（Linux 上通常瞬时返回，所以只在 macOS 暴露）。
    这里跳过 getfqdn，直接用绑定地址。
    """

    daemon_threads = True
    allow_reuse_address = True

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        host, port = self.server_address[:2]
        self.server_name = host
        self.server_port = port


def make_server(config: Config, store: Store) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {"store": store, "config": config})
    httpd = SyncHttpServer((config.host, config.port), handler)
    if config.tls_cert and config.tls_key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certfile=config.tls_cert, keyfile=config.tls_key)
        httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    return httpd


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="LinguaReader sync server")
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve", help="start the HTTP(S) server")
    serve.add_argument("--host")
    serve.add_argument("--port", type=int)
    create = sub.add_parser("create-user", help="create an account (registration is closed by default)")
    create.add_argument("username")
    create.add_argument("--password")
    reset = sub.add_parser("reset-password", help="reset an account password")
    reset.add_argument("username")
    reset.add_argument("--password")
    args = parser.parse_args(argv)

    config = Config()
    if getattr(args, "host", None):
        config.host = args.host
    if getattr(args, "port", None):
        config.port = args.port
    store = Store(config.db_path, config.blob_dir, config.quota_mb, config.token_ttl_days)

    if args.command == "create-user":
        password = args.password or getpass.getpass("password: ")
        user_id = store.create_user(args.username, password)
        print("created user %s (%s)" % (args.username, user_id))
        return 0
    if args.command == "reset-password":
        import secrets as _secrets
        password = args.password or getpass.getpass("new password: ")
        with store._lock:
            row = store.db.execute("SELECT id FROM users WHERE username = ?", (args.username,)).fetchone()
            if row is None:
                print("no such user: %s" % args.username, file=sys.stderr)
                return 1
            salt = _secrets.token_bytes(16)
            store.db.execute(
                "UPDATE users SET pw_salt = ?, pw_hash = ? WHERE id = ?",
                (salt, hash_password(password, salt), row["id"]),
            )
            store.db.commit()
        print("password reset for %s" % args.username)
        return 0

    httpd = make_server(config, store)
    scheme = "https" if (config.tls_cert and config.tls_key) else "http"
    # flush=True：stdout 被重定向到文件时是块缓冲，不刷会导致排障时日志为空。
    print("LinguaReader sync server listening on %s://%s:%d" % (scheme, config.host, config.port), flush=True)
    print("db=%s blobs=%s register=%s" % (config.db_path, config.blob_dir, config.allow_register), flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
