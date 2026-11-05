"""Bluetooth receiver (Windows). The phone runs an RFCOMM server with SERVICE_UUID;
this side finds the paired phone through SDP and connects (WinRT)."""
import asyncio
import uuid


SERVICE_UUID = uuid.UUID("6c3a0f7e-5b1d-4a7e-9d54-0a2f7f5e1c01")
READ_TIMEOUT = 5.0


class BtConn:
    """Gives a WinRT stream socket the same recv_exact() as the TCP path."""

    def __init__(self, loop, sock, reader):
        self.loop, self.sock, self.reader = loop, sock, reader

    async def _read(self, n):
        got = await self.reader.load_async(n)
        if got < n:
            raise ConnectionError("phone closed the Bluetooth link")
        buf = bytearray(n)
        self.reader.read_bytes(buf)
        return bytes(buf)

    def recv_exact(self, n):
        try:
            return self.loop.run_until_complete(asyncio.wait_for(self._read(n), READ_TIMEOUT))
        except asyncio.TimeoutError:
            raise ConnectionError("Bluetooth link timed out")

    def sendall(self, data):
        """Best effort (only the small capabilities message goes this way)."""
        from winrt.windows.storage.streams import DataWriter

        async def _write():
            w = DataWriter(self.sock.output_stream)
            w.write_bytes(data)
            await w.store_async()
            w.detach_stream()

        try:
            self.loop.run_until_complete(asyncio.wait_for(_write(), 3.0))
        except Exception:
            pass

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


async def _find_service():
    """The Droidboard RFCOMM service of a paired phone, or None.

    Windows keeps an old copy of each device's service list (SDP), and the phone only offers the service
    while it waits in Bluetooth mode, so the cached list usually lacks it. When it does, the paired phones
    are asked directly (uncached)."""
    from winrt.windows.devices.bluetooth import BluetoothCacheMode, BluetoothDevice, BluetoothMajorClass
    from winrt.windows.devices.bluetooth.rfcomm import RfcommDeviceService, RfcommServiceId
    from winrt.windows.devices.enumeration import DeviceInformation
    service_id = RfcommServiceId.from_uuid(SERVICE_UUID)
    infos = await DeviceInformation.find_all_async_aqs_filter(RfcommDeviceService.get_device_selector(service_id))
    if len(infos):
        service = await RfcommDeviceService.from_id_async(infos[0].id)
        if service is not None:
            return service
    paired = await DeviceInformation.find_all_async_aqs_filter(
        BluetoothDevice.get_device_selector_from_pairing_state(True))
    for info in paired:
        device = await BluetoothDevice.from_id_async(info.id)
        if device is None or device.class_of_device.major_class != BluetoothMajorClass.PHONE:
            continue     # earbuds, mice, ...: do not wake them up
        found = await device.get_rfcomm_services_for_id_async(service_id, BluetoothCacheMode.UNCACHED)
        if len(found.services):
            return found.services[0]
    return None


async def _connect(service):
    from winrt.windows.networking.sockets import SocketProtectionLevel, StreamSocket
    from winrt.windows.storage.streams import DataReader
    sock = StreamSocket()
    await sock.connect_async(service.connection_host_name, service.connection_service_name,
                             SocketProtectionLevel.BLUETOOTH_ENCRYPTION_ALLOW_NULL_AUTHENTICATION)
    return sock, DataReader(sock.input_stream)


def serve_bluetooth(stats, stop_event, handle):
    """Loops: find the paired phone, connect, hand the connection to `handle`."""
    try:
        import winrt.windows.devices.bluetooth.rfcomm  # noqa: F401
    except ImportError:
        print("[bt] winrt packages missing: pip install winrt-runtime winrt-Windows.Devices."
              "Bluetooth.Rfcomm winrt-Windows.Devices.Enumeration winrt-Windows.Networking."
              "Sockets winrt-Windows.Storage.Streams")
        return
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    last = None
    while not stop_event.is_set():
        if stats.connected:     # already streaming over USB/Wi-Fi: leave the Bluetooth radio alone
            stop_event.wait(1.0)
            continue
        try:
            service = loop.run_until_complete(_find_service())
            if service is None:
                stop_event.wait(3.0)
                continue
            sock, reader = loop.run_until_complete(_connect(service))
        except Exception as e:  # phone not in Bluetooth mode, out of range, ...
            if str(e) != last:
                print(f"\n[bt] {e}")
                last = str(e)
            stop_event.wait(3.0)
            continue
        conn = BtConn(loop, sock, reader)
        print("\n[bt] phone connected")
        stats.connected, stats.transport = True, "Bluetooth"
        try:
            handle(conn)
        except Exception as e:
            print(f"\n[bt] disconnected: {e}")
        finally:
            stats.connected = False
            conn.close()
            last = None
