"""Transport contracts; real drone operations require hardware verification."""
from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum


@dataclass(frozen=True)
class Capabilities:
    discovery: bool = False
    connection: bool = False
    flight_commands: bool = False


class BasicCommand(Enum):
    TAKEOFF = "takeoff"
    LAND = "land"


class DroneAdapter(ABC):
    @property
    @abstractmethod
    def capabilities(self) -> Capabilities: ...

    @abstractmethod
    async def connect(self) -> None: ...

    @abstractmethod
    async def disconnect(self) -> None: ...

    @abstractmethod
    async def send_command(self, command: BasicCommand) -> None: ...


class Neo2Adapter(DroneAdapter):
    """Fail closed until connection and flight behavior are verified on hardware."""
    @property
    def capabilities(self) -> Capabilities:
        return Capabilities(discovery=True)

    async def connect(self) -> None:
        raise NotImplementedError("Neo 2 handshake has not been verified on this hardware")

    async def disconnect(self) -> None:
        pass

    async def send_command(self, command: BasicCommand) -> None:
        raise NotImplementedError("Neo 2 flight commands have not been verified")


class MockAdapter(DroneAdapter):
    """Offline test double; it never connects to a drone."""
    def __init__(self) -> None:
        self.connected = False
        self.commands: list[BasicCommand] = []

    @property
    def capabilities(self) -> Capabilities:
        return Capabilities(connection=True, flight_commands=True)

    async def connect(self) -> None:
        self.connected = True

    async def disconnect(self) -> None:
        self.connected = False

    async def send_command(self, command: BasicCommand) -> None:
        if not self.connected:
            raise RuntimeError("Mock drone is not connected")
        if not isinstance(command, BasicCommand):
            raise ValueError("Unsupported command")
        self.commands.append(command)
