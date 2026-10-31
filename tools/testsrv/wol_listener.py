#!/usr/bin/env python3
"""Receive and validate exactly three NetKit WoL magic packets on loopback."""

import argparse
import socket
import sys
import time


HOST = "127.0.0.1"
PORT = 9009
EXPECTED_MAC = bytes.fromhex("aabbccddeeff")
EXPECTED_PACKET = b"\xff" * 6 + EXPECTED_MAC * 16


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--timeout",
        type=float,
        default=60.0,
        help="seconds to wait for one datagram (default: 60)",
    )
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as listener:
        listener.settimeout(args.timeout)
        listener.bind((HOST, PORT))
        print(f"LISTENING {HOST}:{PORT} timeout={args.timeout:g}s", flush=True)
        packets = []
        times = []
        try:
            for _ in range(3):
                packet, peer = listener.recvfrom(2048)
                if packet != EXPECTED_PACKET or peer[0] != HOST:
                    print("FAIL: invalid packet or non-loopback peer", flush=True)
                    return 1
                packets.append((packet, peer))
                times.append(time.monotonic())
            listener.settimeout(0.5)
            try:
                listener.recvfrom(2048)
            except TimeoutError:
                pass  # Exactly the configured three datagrams; no extra packet.
            else:
                print("FAIL: unexpected extra WoL packet", flush=True)
                return 1
        except TimeoutError:
            print("FAIL: timeout waiting for all three WoL packets", flush=True)
            return 1
        print("packet_count=3", flush=True)
        print("interval_ms=" + ",".join(str(round((b-a)*1000,3)) for a,b in zip(times,times[1:])), flush=True)

    packet, peer = packets[-1]
    print(f"peer={peer[0]}:{peer[1]}")
    print(f"length={len(packet)}")
    print(f"payload_hex={packet.hex()}")
    if packet != EXPECTED_PACKET:
        print("FAIL: expected exactly 6*ff + 16*aa:bb:cc:dd:ee:ff (102 bytes)")
        return 1

    print("PASS: exact 102-byte WoL packet for AA:BB:CC:DD:EE:FF")
    return 0


if __name__ == "__main__":
    sys.exit(main())
