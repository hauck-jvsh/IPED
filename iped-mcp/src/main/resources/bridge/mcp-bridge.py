#!/usr/bin/env python3
"""Bridges a harness that speaks stdio to an IPED MCP server listening on a socket.

    opencode --stdio--> mcp-bridge --TCP--> McpServerMain
    (isolated env)     (isolated env)      (beside the evidence)

Same job as ``iped.mcp.McpRelayMain``, in the one interpreter every Linux image
already carries. It exists because the isolated environment is the place where
weight is least welcome: the relay is a handshake line and two byte pumps, and
making that the reason to install a JRE there costs about two hundred megabytes
and a second runtime to keep patched, in the environment whose whole value is
being small enough to reason about.

Use whichever fits: the Java relay when the isolated environment already has a
JVM, this when it has Python. They speak the same protocol to the same server.

Two properties matter more than anything else here, and both are easy to break
by accident:

  * **Nothing but protocol bytes may reach stdout.** On this process stdout IS
    the channel back to the harness, so a single stray print corrupts the
    session. Diagnostics go to stderr.

  * **When the harness closes stdin, the socket must be half-closed.** Closing
    the child's stdin is how every supported harness signals shutdown. Without
    the half-close the server never sees end-of-input, waits for a request that
    will never arrive, and holds the case and its write claim until the idle
    timeout expires. This is FR-035, and no request/response test catches it: a
    hung bridge answers every request correctly and only fails to end.

Configuration comes from the environment, so the shared secret never has to sit
in a harness configuration file:

    IPED_MCP_HOST           host running the MCP server        (required)
    IPED_MCP_PORT           port it listens on                 (required)
    IPED_MCP_SHARED_SECRET  the secret itself                  (this or the next)
    IPED_MCP_SECRET_FILE    file holding the shared secret
    IPED_MCP_OPERATOR       claimed operator name              (optional)

Requires Python 3.6 or later. No third-party packages.
"""

import os
import socket
import sys
import threading

PROTOCOL = "IPED-MCP/1"
BUFFER = 8192


def fail(message, code):
    print("mcp-bridge: " + message, file=sys.stderr, flush=True)
    sys.exit(code)


def resolve_secret():
    """The same two sources the server reads, in the same order."""
    inline = os.environ.get("IPED_MCP_SHARED_SECRET", "").strip()
    if inline:
        return inline
    path = os.environ.get("IPED_MCP_SECRET_FILE", "").strip()
    if not path:
        return None
    try:
        with open(path, "rb") as handle:
            return handle.read().decode("utf-8").strip() or None
    except OSError as error:
        fail("the secret file at %s could not be read: %s" % (path, error), 2)


def read_line(sock):
    """Reads the handshake answer one byte at a time.

    Deliberately unbuffered: a buffered reader would swallow the first bytes of
    the JSON-RPC stream that follows the newline.
    """
    line = bytearray()
    while True:
        byte = sock.recv(1)
        if not byte:
            return line.decode("utf-8", "replace") if line else None
        if byte == b"\n":
            return line.decode("utf-8", "replace")
        line += byte


def pump(source, sink, flush):
    """Byte for byte, flushed per chunk: the protocol must not sit in a buffer."""
    while True:
        chunk = source(BUFFER)
        if not chunk:
            return
        sink(chunk)
        flush()


def main():
    host = os.environ.get("IPED_MCP_HOST", "").strip()
    port = os.environ.get("IPED_MCP_PORT", "").strip()
    if not host or not port:
        fail("the bridge needs to know where the server is; set IPED_MCP_HOST and IPED_MCP_PORT", 2)

    secret = resolve_secret()
    if not secret:
        fail("no shared secret resolves, so there is nothing to authenticate with. Set "
             "IPED_MCP_SHARED_SECRET, or IPED_MCP_SECRET_FILE pointing at a file containing it. "
             "It must be the same secret the server resolves", 2)

    try:
        sock = socket.create_connection((host, int(port)))
    except OSError as error:
        fail("could not reach the server at %s:%s: %s" % (host, port, error), 4)

    with sock:
        operator = os.environ.get("IPED_MCP_OPERATOR", "").strip()
        opening = PROTOCOL + " " + secret
        if operator:
            # A claim, and recorded as exactly that: the secret proves the connection
            # was authorized, not who is at the keyboard.
            opening += " " + operator.replace(" ", "_")
        sock.sendall((opening + "\n").encode("utf-8"))

        answer = read_line(sock)
        if answer is None or not answer.startswith(PROTOCOL + " OK"):
            fail("the server refused the connection. The shared secret on this side does not match "
                 "the one the server resolves, or the server is already serving its maximum number "
                 "of sessions", 3)
        print("mcp-bridge: connected to %s:%s" % (host, port), file=sys.stderr, flush=True)

        def upstream():
            try:
                pump(sys.stdin.buffer.read1, sock.sendall, lambda: None)
            except OSError as error:
                print("mcp-bridge: upstream ended: %s" % error, file=sys.stderr, flush=True)
            finally:
                try:
                    sock.shutdown(socket.SHUT_WR)
                except OSError:
                    pass  # Already gone, which is the condition this is announcing anyway.

        thread = threading.Thread(target=upstream, name="mcp-bridge-up", daemon=True)
        thread.start()

        # Downstream runs on the main thread, so this returns only once the server
        # has closed its side -- which is what makes the process exit cleanly rather
        # than being killed.
        out = sys.stdout.buffer
        pump(sock.recv, out.write, out.flush)


if __name__ == "__main__":
    main()
