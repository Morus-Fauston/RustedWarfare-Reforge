import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from windows_client_mcp import Client


class BackgroundMcpTest(unittest.TestCase):
    def test_malformed_requests_leave_the_stdio_connection_usable(self):
        requests = [
            {"id": 1, "method": "initialize", "params": []},
            {"id": None, "method": "ping"},
            {"id": 3, "method": "initialize", "params": {"protocolVersion": "2025-11-25",
                  "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}},
            {"method": "notifications/initialized"},
            {"id": 4, "method": "tools/call", "params": {"name": "unknown"}},
            {"id": 5, "method": "tools/call", "params": {"name": "client_status", "arguments": []}},
            {"id": 6, "method": "tools/call"},
            {"id": 7, "method": "tools/list"},
        ]
        script = Path(__file__).with_name("windows_client_mcp.py")
        with tempfile.TemporaryDirectory() as temporary:
            run = subprocess.run([sys.executable, "-B", str(script), "--evidence", temporary, "--run-id", "sample"],
                                 input="".join(json.dumps(dict(row, jsonrpc="2.0")) + "\n" for row in requests),
                                 text=True, capture_output=True, timeout=10, check=True)
        responses = {row["id"]: row for row in map(json.loads, run.stdout.splitlines())}
        self.assertEqual({1, None, 3, 4, 5, 6, 7}, set(responses))
        malformed, null_id, initialized = (responses[id_] for id_ in (1, None, 3))
        self.assertEqual(-32600, malformed["error"]["code"])
        self.assertEqual(-32600, null_id["error"]["code"])
        self.assertEqual("2025-11-25", initialized["result"]["protocolVersion"])
        for id_ in (4, 5, 6):
            self.assertEqual(-32602, responses[id_]["error"]["code"])
        tools = {tool["name"]: tool for tool in responses[7]["result"]["tools"]}
        self.assertEqual({"client_close", "client_screenshot", "client_status", "client_click", "client_scroll"}, set(tools))
        self.assertEqual({"runId", "generation", "x", "y", "amount"},
                         set(tools["client_scroll"]["inputSchema"]["required"]))

    def test_invalid_scroll_cannot_publish_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            client = Client(Path(temporary), "sample")
            for amount in (0, 21, -21, 1.5, True):
                with self.subTest(amount=amount), self.assertRaisesRegex(ValueError, "scroll amount"):
                    client.call("client_scroll", {"runId": "sample", "generation": 0, "x": 1, "y": 2,
                                                  "amount": amount})
            self.assertEqual(0, client.request_id)

    def test_failed_request_publication_closes_the_local_session(self):
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary)
            bridge = evidence / "background-input"
            bridge.mkdir()
            (bridge / "ready.json").write_text('{"runId":"sample"}', encoding="utf-8")
            (bridge / "request-1.tmp").mkdir()
            client = Client(evidence, "sample")
            with self.assertRaises(OSError):
                client.call("client_status", {"runId": "sample"})
            with self.assertRaisesRegex(ValueError, "closed"):
                client.call("client_status", {"runId": "sample"})
            self.assertFalse((bridge / "request-2.json").exists())


if __name__ == "__main__":
    unittest.main()
