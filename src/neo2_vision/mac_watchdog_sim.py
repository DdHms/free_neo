"""Mac watchdog proof of concept: simulation only, never connects a drone.

Exercises the upstream owner in a separate process using a framed Unix stream
socketpair. This is NOT the production flight owner or an airborne recovery
implementation; it cannot open a real drone backend.
"""
import json
import os
from pathlib import Path
import socket
import select
import struct
import subprocess
import sys
import time
import tempfile


def _transfer(sock, data=None, length=0, *, deadline=None):
    if deadline is None:
        deadline = time.monotonic() + (.025 if sock.gettimeout() == 0 else .25)
    received = bytearray()
    sent = 0
    while (data is not None and sent < len(data)) or (data is None and len(received) < length):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('framed socket timed out')
        readable, writable, _ = select.select([sock] if data is None else [], [sock] if data is not None else [], [], remaining)
        if data is not None and writable:
            count = sock.send(data[sent:])
            if count == 0: raise EOFError
            sent += count
        elif data is None and readable:
            chunk = sock.recv(length-len(received))
            if not chunk: raise EOFError
            received.extend(chunk)
    return bytes(received)


def _send(sock, value):
    from neo2_control.control_owner import _encode
    body = _encode(value)
    _transfer(sock, struct.pack('!I', len(body)) + body)


def _receive(sock):
    timeout = sock.gettimeout()
    if timeout is not None and timeout > 0 and not select.select([sock], [], [], timeout)[0]:
        raise TimeoutError('watchdog response timed out')
    deadline = time.monotonic() + (.025 if timeout == 0 else .25)
    length = struct.unpack('!I', _transfer(sock, length=4, deadline=deadline))[0]
    if not 0 < length <= 32768: raise ValueError('invalid message size')
    from neo2_control.control_owner import _decode
    return _decode(_transfer(sock, length=length, deadline=deadline))


def run_simulation(journal_path: Path) -> dict:
    parent, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
    parent.settimeout(3)
    process = None
    try:
        process = subprocess.Popen([sys.executable, '-m', 'neo2_vision.mac_watchdog_sim',
                                    '--owner', str(child.fileno()), str(journal_path)],
                                   pass_fds=(child.fileno(),), stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   start_new_session=True)
        child.close()
        ready = _receive(parent)
        if ready.get('kind') != 'ready' or ready['status'].get('synthetic') is not True:
            raise RuntimeError('synthetic owner handshake failed')
        epoch = ready['epoch']
        sequence = 0
        def request(operation, parameters=None):
            nonlocal sequence
            sequence += 1
            _send(parent, {'kind':'request','epoch':epoch,'id':sequence,
                           'expires_at':time.monotonic()+.25,'operation':operation,
                           'parameters':parameters or {}})
            reply = _receive(parent)
            if reply.get('id') != sequence or reply.get('error'):
                raise RuntimeError('synthetic owner rejected request')
            return reply
        def action(name, parameters=None):
            return request('action', {'action':name,'parameters':parameters or {}})['result']
        action('open')
        token = action('prepare', {'operation':'arm'})['confirmation_token']
        lease = action('arm', {'confirmation_token':token})['lease']
        token = action('prepare', {'operation':'takeoff','lease':lease})['confirmation_token']
        action('takeoff', {'confirmation_token':token,'lease':lease})
        deadline = time.monotonic()+3
        while time.monotonic() < deadline:
            status = request('heartbeat')['status']
            if status['state'] == 'flying':
                break
            time.sleep(.05)
        else:
            raise RuntimeError('simulated takeoff not observed')
        action('keepalive', {'lease': lease})
        # Stop sending parent heartbeats. The independent owner must time out,
        # request simulated landing and confirm synthetic ground state.
        if not select.select([parent], [], [], 3)[0]:
            raise TimeoutError('synthetic watchdog did not finish')
        final = _receive(parent)
        if final.get('kind') != 'final':
            raise RuntimeError('synthetic owner did not finish')
        status = final['status']
        if process.wait(timeout=5) != 0:
            raise RuntimeError('synthetic owner cleanup failed')
        return {'synthetic': True, 'parent_timeout': status.get('fault_reason'),
                'landing_confirmed': status.get('landing_confirmed'),
                'possible_airborne': status.get('possible_airborne')}
    finally:
        parent.close()
        child.close()
        if process is not None and process.poll() is None:
            process.terminate()
            process.wait(timeout=3)


def main() -> int:
    if len(sys.argv) == 4 and sys.argv[1] == '--owner':
        import neo2_control.control_owner as owner
        owner._send = _send
        owner._receive = _receive
        raise SystemExit(owner.run_owner(int(sys.argv[2]), sys.argv[3]))
    if len(sys.argv) == 2 and sys.argv[1] == '--demo':
        with tempfile.TemporaryDirectory(prefix='neo2-watchdog-simulation-') as directory:
            result = run_simulation(Path(directory) / 'journal.jsonl')
        print(json.dumps(result, indent=2))
        return 0 if result['parent_timeout'] == 'controller_parent_timeout' and result['landing_confirmed'] else 1
    raise SystemExit('Use --demo for the synthetic watchdog test')


if __name__ == '__main__':
    main()
