"""Private same-user Unix socket endpoint for future owner recovery.

Transport and peer authentication only: this does not implement or authorize
flight-owner adoption, prove process identity across PID reuse, or send drone
commands.
"""
import hashlib
import os
from pathlib import Path
import socket
import stat
import uuid
from neo2_vision.mac_peer import peer_identity


def _private_directory(directory: Path) -> None:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise RuntimeError('unsafe recovery socket directory')


def endpoint(directory, address: str) -> Path:
    identity = uuid.UUID(address)
    if str(identity) != address.lower():
        raise ValueError('invalid_macos_ble_identifier')
    path = Path(directory) / (hashlib.sha256(identity.bytes).hexdigest()[:24] + '.sock')
    if len(os.fsencode(path)) >= 104:
        raise ValueError('recovery socket path too long')
    return path


class RecoveryListener:
    def __init__(self, directory, address):
        self.directory = Path(directory)
        self.path = endpoint(directory, address)
        self.socket = None
        self.inode = None

    def __enter__(self):
        _private_directory(self.directory)
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            # Do not unlink any existing endpoint: it may belong to a live owner.
            listener.bind(str(self.path))
            info = self.path.lstat()
            self.inode = (info.st_dev, info.st_ino)
            os.chmod(self.path, 0o600)
            listener.listen(1)
            self.socket = listener
            return self
        except BaseException:
            listener.close()
            if self.inode is not None:
                self._unlink_own()
            raise

    def accept(self):
        connection, _ = self.socket.accept()
        try:
            pid, uid, gid = peer_identity(connection)
            if uid != os.geteuid():
                raise PermissionError('recovery peer belongs to a different user')
            return connection, (pid, uid, gid)
        except BaseException:
            connection.close()
            raise

    def _unlink_own(self):
        try:
            info = self.path.lstat()
            if stat.S_ISSOCK(info.st_mode) and (info.st_dev, info.st_ino) == self.inode:
                self.path.unlink()
        except FileNotFoundError:
            pass

    def __exit__(self, *_):
        if self.socket is not None:
            self.socket.close()
        self._unlink_own()


def connect_recovery(directory, address):
    directory = Path(directory)
    _private_directory(directory)
    path = endpoint(directory, address)
    info = path.lstat()
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise RuntimeError('unsafe recovery endpoint')
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        connection.connect(str(path))
        pid, uid, gid = peer_identity(connection)
        if uid != os.geteuid():
            raise PermissionError('recovery owner belongs to a different user')
        return connection, (pid, uid, gid)
    except BaseException:
        connection.close()
        raise
