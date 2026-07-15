import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def embedding(text: str) -> list[float]:
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    values = [(digest[index] / 127.5) - 1.0 for index in range(8)]
    length = sum(value * value for value in values) ** 0.5 or 1.0
    return [value / length for value in values]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        if self.path.endswith("/embeddings"):
            inputs = payload.get("input", [])
            if isinstance(inputs, str):
                inputs = [inputs]
            body = {"data": [{"index": index, "embedding": embedding(text)} for index, text in enumerate(inputs)]}
        elif self.path.endswith("/chat/completions"):
            body = {"choices": [{"message": {"role": "assistant", "content": "Local E2E response."}}]}
        else:
            self.send_error(404)
            return
        encoded = json.dumps(body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *_):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 18081), Handler).serve_forever()
