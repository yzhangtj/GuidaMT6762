#!/usr/bin/env python3
"""
Host-side radar stream decoder for Guida glasses via ADB.

- Opens an adb exec-out stream: `adb exec-out cat /dev/ttyS0`
- Parses the radar protocol used in the app:
  Header: AA AA AA AA, Addr, CmdId, Rsv, Rsv, LenLE(2), Data, Checksum
  Checksum: (sum of bytes from index 4 to last-1) & 0xFF == last byte
  Data for CmdId 0x02: N targets of 6 bytes each (LE): dist(u16), speed(i16), angle(i16)
  Units: cm, m/s, degrees (divide raw by 100)
- Prints once per second: d=XX.XXcm v=YY.YYm/s a=ZZ.ZZ° for all targets

Windows PowerShell usage examples:
  py -3 .\radar_host_decode.py --adb "D:\\platform-tools-latest-windows\\platform-tools\\adb.exe" --port /dev/ttyS0
  py -3 .\radar_host_decode.py --adb "D:\\platform-tools-latest-windows\\platform-tools\\adb.exe" --port /dev/ttyS1

"""

from __future__ import annotations

import argparse
import struct
import subprocess
import sys
import time
from typing import List, Tuple


HEADER = b"\xAA\xAA\xAA\xAA"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Stream and decode radar frames over ADB")
    parser.add_argument("--adb", default="adb", help="Path to adb executable (default: adb in PATH)")
    parser.add_argument("--port", default="/dev/ttyS0", help="Serial device on glasses (default: /dev/ttyS0)")
    parser.add_argument("--interval", type=float, default=1.0, help="Print interval in seconds (default: 1.0)")
    parser.add_argument("--verbose", action="store_true", help="Print raw frames and checksum diagnostics")
    return parser.parse_args()


def find_header(buf: bytearray) -> int:
    try:
        return buf.index(HEADER)
    except ValueError:
        return -1


def calc_checksum(frame: bytes) -> int:
    # Sum from Device Address (index 4) to end of Data (last-1)
    return sum(frame[4:-1]) & 0xFF


def decode_targets(payload: bytes) -> List[Tuple[float, float, float]]:
    if len(payload) % 6 != 0:
        return []
    results: List[Tuple[float, float, float]] = []
    for i in range(0, len(payload), 6):
        raw_dist = struct.unpack_from('<H', payload, i)[0]
        raw_speed = struct.unpack_from('<h', payload, i + 2)[0]
        raw_angle = struct.unpack_from('<h', payload, i + 4)[0]
        dist_cm = raw_dist / 100.0
        speed_ms = raw_speed / 100.0
        angle_deg = raw_angle / 100.0
        results.append((dist_cm, speed_ms, angle_deg))
    return results


def main() -> int:
    args = parse_args()

    cmd = [args.adb, 'exec-out', 'cat', args.port]
    print(f"Starting ADB stream: {' '.join(cmd)}", flush=True)
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
    except FileNotFoundError:
        print(f"Error: adb not found at '{args.adb}'. Set --adb to full path to adb.exe.", file=sys.stderr)
        return 1

    buf = bytearray()
    last_print = 0.0

    try:
        while True:
            chunk = proc.stdout.read(1024) if proc.stdout else b''
            if not chunk:
                # If the process terminated or no data, try to read stderr for hints
                if proc.poll() is not None:
                    err = proc.stderr.read().decode(errors='ignore') if proc.stderr else ''
                    print(f"adb exec-out ended (code={proc.returncode}). stderr=\n{err}", file=sys.stderr)
                    break
                # No data yet; small sleep to avoid busy loop
                time.sleep(0.02)
                continue

            buf.extend(chunk)

            # Extract frames
            while True:
                i = find_header(buf)
                if i < 0:
                    # Keep a small tail in case header spans reads
                    buf[:] = buf[-3:]
                    break
                if len(buf) - i < 11:
                    # Not enough for header+prefix yet
                    buf[:] = buf[i:]
                    break
                cmd_id = buf[i + 5]
                length = struct.unpack_from('<H', buf, i + 8)[0]
                total_len = 10 + length + 1
                if len(buf) - i < total_len:
                    buf[:] = buf[i:]
                    break

                frame = bytes(buf[i:i + total_len])
                del buf[: i + total_len]

                # Validate checksum
                expected = frame[-1]
                calculated = calc_checksum(frame)
                if expected != calculated:
                    if args.verbose:
                        print(
                            f"Checksum mismatch: exp={expected:02X} calc={calculated:02X} "
                            f"len={len(frame)} frame={' '.join(f'{b:02X}' for b in frame)}",
                            file=sys.stderr,
                        )
                    continue

                # Only handle target info frames
                if cmd_id != 0x02:
                    continue

                payload = frame[10:-1]
                targets = decode_targets(payload)

                now = time.time()
                if now - last_print >= args.interval:
                    if targets:
                        print(' '.join(
                            f"d={d:.2f}cm v={s:.2f}m/s a={a:.2f}°" for d, s, a in targets
                        ), flush=True)
                    else:
                        print('(no targets)', flush=True)
                    last_print = now

    except KeyboardInterrupt:
        pass
    finally:
        try:
            proc.terminate()
        except Exception:
            pass
    return 0


if __name__ == '__main__':
    raise SystemExit(main())


