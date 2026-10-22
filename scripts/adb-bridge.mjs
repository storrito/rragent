#!/usr/bin/env node
// The laptop's adb server for the page of rragent: the screencast panel
// shows and records an Android phone's screen through it (see
// rragent.client.usb-screen) while the adb server keeps the phone, so
// adb, its tunnels and Android Studio go on as before. A page cannot
// open a TCP socket; this script turns each WebSocket the page opens on
// ws://127.0.0.1:<port> into one connection to the adb server, bytes
// both ways, nothing else.
//
// adb is a shell on the phone: only pages of the origins named are let
// in (a browser sends its page's origin and a page cannot change it),
// and only from this machine.
//
//   node scripts/adb-bridge.mjs --origin https://<sprite>.sprites.app [--origin …]
//
// Environment: RRAGENT_ADB_BRIDGE_PORT (15037), ADB_SERVER_PORT (5037).
// No dependencies: Node 18 or later.
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { connect } from "node:net";

const port = Number(process.env.RRAGENT_ADB_BRIDGE_PORT ?? 15037);
const adbPort = Number(process.env.ADB_SERVER_PORT ?? 5037);
const origins = new Set();
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === "--origin" && process.argv[i + 1]) {
    origins.add(new URL(process.argv[++i]).origin);
  }
}
if (origins.size === 0) {
  console.error("Usage: node scripts/adb-bridge.mjs --origin https://<sprite>.sprites.app [--origin …]");
  process.exit(1);
}

const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

// A frame of the server: binary or close, never masked.
function frame(opcode, payload) {
  const length = payload.length;
  const head = length < 126 ? Buffer.alloc(2) : length < 65536 ? Buffer.alloc(4) : Buffer.alloc(10);
  head[0] = 0x80 | opcode;
  if (length < 126) {
    head[1] = length;
  } else if (length < 65536) {
    head[1] = 126;
    head.writeUInt16BE(length, 2);
  } else {
    head[1] = 127;
    head.writeBigUInt64BE(BigInt(length), 2);
  }
  return Buffer.concat([head, payload]);
}

// The frames of the client from `buffer`: the whole ones, and what is left.
function frames(buffer) {
  const out = [];
  let offset = 0;
  while (buffer.length - offset >= 2) {
    const opcode = buffer[offset] & 0x0f;
    const masked = (buffer[offset + 1] & 0x80) !== 0;
    let length = buffer[offset + 1] & 0x7f;
    let at = offset + 2;
    if (length === 126) {
      if (buffer.length < at + 2) break;
      length = buffer.readUInt16BE(at);
      at += 2;
    } else if (length === 127) {
      if (buffer.length < at + 8) break;
      length = Number(buffer.readBigUInt64BE(at));
      at += 8;
    }
    const mask = masked ? buffer.subarray(at, at + 4) : null;
    if (masked) at += 4;
    if (buffer.length < at + length) break;
    const payload = Buffer.from(buffer.subarray(at, at + length));
    if (mask) {
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    }
    out.push({ opcode, payload });
    offset = at + length;
  }
  return { frames: out, rest: buffer.subarray(offset) };
}

const server = createServer((_request, response) => {
  response.writeHead(426, { "Content-Type": "text/plain" });
  response.end("rragent's adb bridge: a WebSocket only\n");
});

server.on("upgrade", (request, socket) => {
  const origin = request.headers.origin;
  const key = request.headers["sec-websocket-key"];
  if (!origins.has(origin) || !key || request.headers.upgrade?.toLowerCase() !== "websocket") {
    console.error(`refused: ${origin ?? "no origin"}`);
    socket.end("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
    return;
  }
  const accept = createHash("sha1").update(key + GUID).digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );
  socket.setNoDelay(true);

  const adb = connect({ host: "127.0.0.1", port: adbPort });
  adb.setNoDelay(true);
  let pending = Buffer.alloc(0);
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    if (!socket.destroyed) socket.end(frame(0x8, Buffer.alloc(0)));
    adb.destroy();
  };

  adb.on("data", (chunk) => {
    if (!socket.write(frame(0x2, chunk))) {
      adb.pause();
      socket.once("drain", () => adb.resume());
    }
  });
  adb.on("close", close);
  adb.on("error", (error) => {
    console.error(`adb server on port ${adbPort}: ${error.message}`);
    close();
  });

  socket.on("data", (chunk) => {
    const parsed = frames(Buffer.concat([pending, chunk]));
    pending = parsed.rest;
    for (const { opcode, payload } of parsed.frames) {
      if (opcode === 0x8) {
        close();
      } else if (opcode === 0x9) {
        socket.write(frame(0xa, payload));
      } else if (opcode === 0x0 || opcode === 0x1 || opcode === 0x2) {
        if (!adb.write(payload)) {
          socket.pause();
          adb.once("drain", () => socket.resume());
        }
      }
    }
  });
  socket.on("close", close);
  socket.on("error", close);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`adb bridge on ws://127.0.0.1:${port} to the adb server on port ${adbPort}, for ${[...origins].join(", ")}`);
});
