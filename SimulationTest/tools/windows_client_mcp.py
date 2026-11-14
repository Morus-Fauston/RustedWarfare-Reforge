"""Local stdio MCP adapter for an explicitly launched hidden Windows client."""

import argparse
import base64
import json
from pathlib import Path
import struct
import sys
import time


POINTER_ARGUMENTS = {name: {"type": "integer", "minimum": 0} for name in ("generation", "x", "y")}
# Menu clicks can verify a complete local content package synchronously.
CLICK_ACK_SECONDS = 30
DEFAULT_ACK_SECONDS = 5
TOOLS = {
    "client_status": ("Read the owned client's run and framebuffer identity.", {}),
    "client_click": ("Send one click through the current client input processor.", {
        **POINTER_ARGUMENTS,
        "button": {"enum": ["left", "right"]},
    }),
    "client_scroll": ("Send vertical wheel input through the current client input processor.", {
        **POINTER_ARGUMENTS,
        "amount": {"type": "integer", "minimum": -20, "maximum": 20, "not": {"const": 0}},
    }),
    "client_type": ("Type text through the focused client input processor.", {
        "generation": POINTER_ARGUMENTS["generation"],
        "text": {"type": "string", "minLength": 1, "maxLength": 512},
    }),
    "client_screenshot": ("Read one actual framebuffer as PNG.", {}),
    "client_close": ("Close the owned hidden Windows client.", {}),
}


class Client:
    def __init__(self, directory, run_id):
        self.bridge = directory / "background-input"
        self.run_id = run_id
        self.request_id = 0
        self.unavailable = False

    def call(self, operation, arguments):
        if self.unavailable:
            raise ValueError("The background input session is closed or timed out")
        if arguments.pop("runId", None) != self.run_id:
            raise ValueError("Input session identity has changed")
        allowed = TOOLS[operation][1]
        if set(arguments) != set(allowed):
            raise ValueError("Unexpected tool arguments")
        if operation in ("client_click", "client_scroll") and any(
                type(arguments[key]) is not int or arguments[key] < 0 for key in POINTER_ARGUMENTS):
            raise ValueError("Expected nonnegative integer generation and x/y")
        if operation == "client_click" and arguments["button"] not in ("left", "right"):
            raise ValueError("Expected a supported button")
        if operation == "client_scroll" and (type(arguments["amount"]) is not int or
                                             not -20 <= arguments["amount"] <= 20 or arguments["amount"] == 0):
            raise ValueError("Expected a nonzero scroll amount between -20 and 20")
        if operation == "client_type":
            if type(arguments["generation"]) is not int or arguments["generation"] < 0:
                raise ValueError("Expected a nonnegative integer generation")
            value = arguments["text"]
            if not isinstance(value, str) or not 1 <= len(value.encode("utf-16-le", "surrogatepass")) // 2 <= 512 or any(
                    (ord(character) < 32 and character != "\b") or
                    127 <= ord(character) <= 159 or 0xD800 <= ord(character) <= 0xDFFF
                    for character in value):
                raise ValueError("Expected 1 to 512 typed characters")
        ready = json.loads((self.bridge / "ready.json").read_bytes())
        if ready["runId"] != self.run_id:
            raise ValueError("Owned client identity no longer matches")
        request_id = self.request_id + 1
        request = self.bridge / f"request-{request_id}.json"
        temporary = request.with_suffix(".tmp")
        try:
            with temporary.open("x", encoding="utf-8") as output:
                json.dump(dict(arguments, id=request_id, runId=self.run_id, action=operation.removeprefix("client_")),
                          output, ensure_ascii=False)
            temporary.rename(request)
        except OSError:
            self.unavailable = True
            raise
        self.request_id = request_id
        response = self.bridge / f"response-{request_id}.json"
        start = time.monotonic()
        timeout = CLICK_ACK_SECONDS if operation == "client_click" else DEFAULT_ACK_SECONDS
        while not response.is_file():
            if time.monotonic() - start >= timeout:
                self.unavailable = True
                raise ValueError("Client failed to acknowledge input before the deadline")
            time.sleep(.025)
        payload = json.loads(response.read_bytes())
        if payload["runId"] != self.run_id or payload["id"] != request_id:
            self.unavailable = True
            raise ValueError("Response does not match this input session")
        if operation == "client_close" and payload["ok"]:
            self.unavailable = True
        content = [{"type": "text", "text": json.dumps(payload)}]
        if operation == "client_screenshot" and payload["ok"]:
            if payload["image"] != f"frame-{request_id}.png":
                raise ValueError("Unexpected framebuffer path")
            pixels = (self.bridge / payload["image"]).read_bytes()
            if pixels[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", pixels[16:24]) != (
                    payload["framebufferWidth"], payload["framebufferHeight"]):
                raise ValueError("PNG size differs from the client framebuffer")
            content.append({"type": "image", "mimeType": "image/png",
                            "data": base64.b64encode(pixels).decode("ascii")})
        return {"content": content, "structuredContent": payload, "isError": not payload["ok"]}


def dispatch(client, message):
    method = message["method"]
    if method == "initialize":
        params = message.get("params", {})
        version = params.get("protocolVersion")
        info = params.get("clientInfo")
        if (not isinstance(version, str) or not isinstance(params.get("capabilities"), dict) or
                not isinstance(info, dict) or not all(isinstance(info.get(key), str) for key in ("name", "version"))):
            raise ValueError("Invalid initialization parameters")
        return {"protocolVersion": version if version in ("2025-06-18", "2025-11-25") else "2025-11-25",
                "capabilities": {"tools": {}}, "serverInfo": {"name": "reforge-hidden-client", "version": "1.0.0"}}
    if method == "tools/list":
        return {"tools": [{
            "name": name, "description": description,
            "inputSchema": {"type": "object", "additionalProperties": False,
                            "properties": {"runId": {"type": "string"}, **arguments},
                            "required": ["runId", *arguments]},
            "annotations": {"readOnlyHint": name in ("client_status", "client_screenshot"),
                            "destructiveHint": False, "openWorldHint": False},
        } for name, (description, arguments) in TOOLS.items()]}
    if method == "tools/call":
        params = message.get("params", {})
        if not isinstance(params.get("name"), str) or params["name"] not in TOOLS:
            raise ValueError("Unknown tool")
        arguments = params.get("arguments", {})
        if not isinstance(arguments, dict):
            raise ValueError("Expected named tool arguments")
        try:
            return client.call(params["name"], dict(arguments))
        except (ValueError, OSError, KeyError, TypeError, struct.error) as error:
            return {"isError": True, "content": [{"type": "text", "text": str(error)}]}
    if method == "ping":
        return {}
    raise KeyError(method)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    client = Client(args.evidence.resolve(), args.run_id)
    initialized = False
    negotiated = False
    for line in sys.stdin.buffer:
        message_id = None
        try:
            message = json.loads(line)
            if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
                print(json.dumps({"jsonrpc": "2.0", "id": None,
                                  "error": {"code": -32600, "message": "Invalid JSON-RPC request"}}), flush=True)
                continue
            if "id" not in message:
                if message.get("method") == "notifications/initialized" and negotiated:
                    initialized = True
                continue
            message_id = message["id"]
            method = message.get("method")
            if (type(message_id) not in (int, str) or not isinstance(method, str)
                    or not isinstance(message.get("params", {}), dict)):
                result = {"error": {"code": -32600, "message": "Invalid JSON-RPC request"}}
            elif method == "initialize":
                if negotiated:
                    result = {"error": {"code": -32600, "message": "Session already initialized"}}
                else:
                    result = {"result": dispatch(client, message)}
                    negotiated = True
            elif not initialized and method != "ping":
                result = {"error": {"code": -32002, "message": "Session initialization is incomplete"}}
            else:
                result = {"result": dispatch(client, message)}
        except json.JSONDecodeError:
            result = {"error": {"code": -32700, "message": "Invalid JSON"}}
        except KeyError:
            result = {"error": {"code": -32601, "message": "Unknown method"}}
        except (ValueError, TypeError):
            result = {"error": {"code": -32602, "message": "Invalid parameters"}}
        print(json.dumps(dict(result, jsonrpc="2.0", id=message_id)), flush=True)


if __name__ == "__main__":
    main()
