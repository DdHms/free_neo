import asyncio
import unittest

from neo2_vision.transport import BasicCommand, MockAdapter, Neo2Adapter


class TransportTests(unittest.TestCase):
    def test_real_adapter_fails_closed(self):
        adapter = Neo2Adapter()
        self.assertFalse(adapter.capabilities.flight_commands)
        with self.assertRaises(NotImplementedError):
            asyncio.run(adapter.connect())
        with self.assertRaises(NotImplementedError):
            asyncio.run(adapter.send_command(BasicCommand.TAKEOFF))

    def test_mock_requires_connection(self):
        async def exercise():
            adapter = MockAdapter()
            with self.assertRaises(RuntimeError):
                await adapter.send_command(BasicCommand.TAKEOFF)
            await adapter.connect()
            await adapter.send_command(BasicCommand.TAKEOFF)
            await adapter.disconnect()
            with self.assertRaises(RuntimeError):
                await adapter.send_command(BasicCommand.LAND)
            self.assertEqual(adapter.commands, [BasicCommand.TAKEOFF])
        asyncio.run(exercise())
