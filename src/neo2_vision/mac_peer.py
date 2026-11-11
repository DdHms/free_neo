"""macOS credentials for connected local sockets.

Peer credentials identify the peer at connection time. They do not provide a
stable process handle or implement the flight-owner adoption protocol.
"""
import ctypes
import os
import socket
import struct
import sys

# SOL_LOCAL and LOCAL_PEERPID from macOS sys/un.h.
SOL_LOCAL = 0
LOCAL_PEERPID = 2


def peer_identity(sock: socket.socket) -> tuple[int, int, int]:
    if sys.platform != 'darwin':
        raise RuntimeError('macOS peer credentials required')
    libc = ctypes.CDLL(None, use_errno=True)
    getpeereid = libc.getpeereid
    getpeereid.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
    getpeereid.restype = ctypes.c_int
    uid, gid = ctypes.c_uint(), ctypes.c_uint()
    if getpeereid(sock.fileno(), ctypes.byref(uid), ctypes.byref(gid)) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))
    pid = struct.unpack('i', sock.getsockopt(SOL_LOCAL, LOCAL_PEERPID, struct.calcsize('i')))[0]
    if pid <= 0:
        raise RuntimeError('invalid socket peer process')
    return pid, uid.value, gid.value
