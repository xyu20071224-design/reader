#!/usr/bin/env python3
"""同步服务端的标准库单测：起真实 HTTP 服务，用 urllib 打真实请求。

运行：python3 -m unittest discover -s sync-server -p 'test_*.py' -v
"""
import hashlib
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server  # noqa: E402


class SyncServerBase(unittest.TestCase):
    allow_register = True

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="lr-sync-test-")
        cfg = server.Config(env={
            "LR_SYNC_DB": os.path.join(self.tmp, "sync.db"),
            "LR_SYNC_BLOB_DIR": os.path.join(self.tmp, "blobs"),
            "LR_SYNC_HOST": "127.0.0.1",
            "LR_SYNC_PORT": "0",
            "LR_SYNC_ALLOW_REGISTER": "1" if self.allow_register else "0",
            "LR_SYNC_BLOB_QUOTA_MB": "64",
        })
        self.config = cfg
        self.store = server.Store(cfg.db_path, cfg.blob_dir, cfg.quota_mb, cfg.token_ttl_days)
        self.httpd = server.make_server(cfg, self.store)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def call(self, method, path, body=None, token=None, raw=False, headers=None):
        url = "http://127.0.0.1:%d%s" % (self.port, path)
        data = None
        hdrs = dict(headers or {})
        if body is not None:
            if raw:
                data = body
            else:
                data = json.dumps(body).encode("utf-8")
                hdrs["Content-Type"] = "application/json"
        if token:
            hdrs["Authorization"] = "Bearer " + token
        request = urllib.request.Request(url, data=data, method=method, headers=hdrs)
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, response.read(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read(), dict(error.headers)

    def register_and_login(self, username="alice", password="secret-password"):
        status, _, _ = self.call("POST", "/api/v1/auth/register", {"username": username, "password": password})
        self.assertEqual(201, status)
        status, payload, _ = self.call("POST", "/api/v1/auth/login", {"username": username, "password": password})
        self.assertEqual(200, status)
        return json.loads(payload)["token"]


class HealthTests(SyncServerBase):
    def test_health_needs_no_auth(self):
        status, payload, _ = self.call("GET", "/api/v1/health")
        self.assertEqual(200, status)
        self.assertEqual("ok", json.loads(payload)["status"])


class AuthTests(SyncServerBase):
    def test_login_rejects_wrong_password(self):
        self.call("POST", "/api/v1/auth/register", {"username": "bob", "password": "secret-password"})
        status, payload, _ = self.call("POST", "/api/v1/auth/login", {"username": "bob", "password": "nope-nope-nope"})
        self.assertEqual(401, status)
        self.assertEqual("bad_credentials", json.loads(payload)["error"]["code"])

    def test_data_endpoints_require_token(self):
        status, _, _ = self.call("GET", "/api/v1/changes?since=0")
        self.assertEqual(401, status)

    def test_logout_revokes_token(self):
        token = self.register_and_login()
        status, _, _ = self.call("POST", "/api/v1/auth/logout", {}, token=token)
        self.assertEqual(204, status)
        status, _, _ = self.call("GET", "/api/v1/changes?since=0", token=token)
        self.assertEqual(401, status)


class RegistrationClosedTests(SyncServerBase):
    allow_register = False

    def test_register_is_closed_by_default_server(self):
        status, payload, _ = self.call("POST", "/api/v1/auth/register", {"username": "eve", "password": "secret-password"})
        self.assertEqual(403, status)
        self.assertEqual("registration_closed", json.loads(payload)["error"]["code"])


class ChangeStreamTests(SyncServerBase):
    def record(self, seq_updated, progress=0.5):
        return {
            "collection": "progress",
            "id": "book-1",
            "updatedAt": seq_updated,
            "deletedAt": 0,
            "payload": {"title": "A Test Book", "progress": progress},
        }

    def test_push_then_pull_by_cursor(self):
        token = self.register_and_login()
        status, payload, _ = self.call("POST", "/api/v1/changes", {"baseSeq": 0, "records": [self.record(1000)]}, token=token)
        self.assertEqual(200, status)
        result = json.loads(payload)
        self.assertEqual(1, len(result["applied"]))
        self.assertEqual([], result["conflicts"])
        self.assertEqual(1, result["nextSeq"])

        status, payload, _ = self.call("GET", "/api/v1/changes?since=0", token=token)
        records = json.loads(payload)["records"]
        self.assertEqual(1, len(records))
        self.assertEqual("book-1", records[0]["id"])
        self.assertEqual(0.5, records[0]["payload"]["progress"])

        status, payload, _ = self.call("GET", "/api/v1/changes?since=1", token=token)
        self.assertEqual([], json.loads(payload)["records"])

    def test_conflict_returns_server_copy_and_keeps_it(self):
        token = self.register_and_login()
        self.call("POST", "/api/v1/changes", {"baseSeq": 0, "records": [self.record(2000, 0.8)]}, token=token)
        status, payload, _ = self.call("POST", "/api/v1/changes", {"baseSeq": 1, "records": [self.record(1000, 0.2)]}, token=token)
        result = json.loads(payload)
        self.assertEqual([], result["applied"])
        self.assertEqual(1, len(result["conflicts"]))
        self.assertEqual(2000, result["conflicts"][0]["updatedAt"])
        self.assertEqual(0.8, result["conflicts"][0]["payload"]["progress"])

        status, payload, _ = self.call("GET", "/api/v1/changes?since=0", token=token)
        self.assertEqual(0.8, json.loads(payload)["records"][0]["payload"]["progress"])

    def test_newer_client_write_wins_and_advances_seq(self):
        token = self.register_and_login()
        self.call("POST", "/api/v1/changes", {"baseSeq": 0, "records": [self.record(1000, 0.2)]}, token=token)
        status, payload, _ = self.call("POST", "/api/v1/changes", {"baseSeq": 1, "records": [self.record(3000, 0.9)]}, token=token)
        result = json.loads(payload)
        self.assertEqual(1, len(result["applied"]))
        self.assertEqual(2, result["nextSeq"])

    def test_unknown_collection_is_rejected(self):
        token = self.register_and_login()
        bad = {"collection": "books", "id": "x", "updatedAt": 1, "deletedAt": 0, "payload": {}}
        status, payload, _ = self.call("POST", "/api/v1/changes", {"records": [bad]}, token=token)
        self.assertEqual(400, status)
        self.assertEqual("bad_collection", json.loads(payload)["error"]["code"])

    def test_state_returns_everything(self):
        token = self.register_and_login()
        self.call("POST", "/api/v1/changes", {"records": [self.record(1000)]}, token=token)
        status, payload, _ = self.call("GET", "/api/v1/state", token=token)
        body = json.loads(payload)
        self.assertEqual(1, len(body["records"]))
        self.assertEqual(1, body["nextSeq"])

    def test_users_are_isolated(self):
        token_a = self.register_and_login("alice", "secret-password")
        token_b = self.register_and_login("bob", "secret-password")
        self.call("POST", "/api/v1/changes", {"records": [self.record(1000)]}, token=token_a)
        status, payload, _ = self.call("GET", "/api/v1/changes?since=0", token=token_b)
        self.assertEqual([], json.loads(payload)["records"])


class BlobTests(SyncServerBase):
    def test_upload_resume_download_and_delete(self):
        token = self.register_and_login()
        data = b"the lantern library, chapter one"
        digest = hashlib.sha256(data).hexdigest()
        head = 8

        status, payload, _ = self.call(
            "PUT", "/api/v1/blobs/book-1?offset=0&total=%d&sha256=%s" % (len(data), digest),
            body=data[:head], raw=True, token=token,
        )
        self.assertEqual(200, status)
        self.assertFalse(json.loads(payload)["complete"])

        status, _, headers = self.call("HEAD", "/api/v1/blobs/book-1", token=token)
        self.assertEqual(200, status)
        self.assertEqual("0", headers.get("X-Blob-Complete"))
        self.assertEqual(str(head), headers.get("X-Blob-Size"))

        status, payload, _ = self.call(
            "PUT", "/api/v1/blobs/book-1?offset=%d&total=%d&sha256=%s" % (head, len(data), digest),
            body=data[head:], raw=True, token=token,
        )
        self.assertEqual(200, status)
        self.assertTrue(json.loads(payload)["complete"])

        status, payload, _ = self.call("GET", "/api/v1/books", token=token)
        books = json.loads(payload)["books"]
        self.assertEqual(1, len(books))
        self.assertEqual("book-1", books[0]["bookId"])
        self.assertEqual(digest, books[0]["sha256"])

        status, payload, headers = self.call("GET", "/api/v1/blobs/book-1", token=token)
        self.assertEqual(200, status)
        self.assertEqual(data, payload)

        status, payload, headers = self.call(
            "GET", "/api/v1/blobs/book-1", token=token, headers={"Range": "bytes=4-9"}
        )
        self.assertEqual(206, status)
        self.assertEqual(data[4:10], payload)

        status, _, _ = self.call("DELETE", "/api/v1/blobs/book-1", token=token)
        self.assertEqual(204, status)
        status, _, headers = self.call("HEAD", "/api/v1/blobs/book-1", token=token)
        self.assertEqual("0", headers.get("X-Blob-Size"))

    def test_resume_offset_mismatch_is_rejected(self):
        token = self.register_and_login()
        status, payload, _ = self.call(
            "PUT", "/api/v1/blobs/book-2?offset=5&total=10", body=b"abc", raw=True, token=token
        )
        self.assertEqual(409, status)
        self.assertEqual("offset_mismatch", json.loads(payload)["error"]["code"])

    def test_checksum_mismatch_discards_partial(self):
        token = self.register_and_login()
        status, payload, _ = self.call(
            "PUT", "/api/v1/blobs/book-3?offset=0&total=3&sha256=deadbeef", body=b"abc", raw=True, token=token
        )
        self.assertEqual(400, status)
        self.assertEqual("checksum_mismatch", json.loads(payload)["error"]["code"])
        status, _, headers = self.call("HEAD", "/api/v1/blobs/book-3", token=token)
        self.assertEqual("0", headers.get("X-Blob-Size"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
