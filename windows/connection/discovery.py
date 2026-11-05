"""How a phone finds this laptop on the LAN, so the user only types the pairing code (no secrets are sent).

Three ways, the phone uses whichever reaches it first:
- a beacon every 2 s: IPv4 broadcast from every address, and IPv6 multicast to all nodes on every interface;
- an answer to a "PMDQ" question sent straight to us (the phone asks every address of its network
  when no beacon arrives, for networks that drop broadcasts);
- both carry every address of this laptop, IPv6 too, because a Wi-Fi without DHCP leaves the laptop
  with a useless 169.254 address while IPv6 still works.
"""
import socket

from connection.pairing import local_addresses, local_ips


DISCOVERY_PORT = 50506
MAGIC = b"PMDS"
QUESTION = b"PMDQ"
MAX_SIZE = 600            # the phone reads up to 1024 bytes


def payload(port, name, addresses=()):
    data = MAGIC + f"|{port}|{name}|{','.join(addresses)}".encode("utf-8", "replace")
    while len(data) > MAX_SIZE and addresses:       # too many addresses: keep the best ones
        addresses = addresses[:-1]
        data = MAGIC + f"|{port}|{name}|{','.join(addresses)}".encode("utf-8", "replace")
    return data


def parse(data):
    """-> (port, name, [addresses]), or None if this is not one of our beacons."""
    if not data.startswith(MAGIC):
        return None
    try:
        parts = data.decode("utf-8", "replace").split("|", 3)
        port, name = int(parts[1]), parts[2]
    except (ValueError, IndexError):
        return None
    addresses = [a for a in parts[3].split(",") if a] if len(parts) > 3 else []
    return port, name, addresses


def _targets(ip):
    a, b, c, _ = ip.split(".")
    return ["255.255.255.255", f"{a}.{b}.{c}.255"]


def _interfaces():
    """Index of every network interface (IPv6 multicast must name the one it leaves through)."""
    try:
        return [index for index, _ in socket.if_nameindex()]
    except (OSError, AttributeError):
        return []


def _send_beacon(data):
    for ip in local_ips():
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                s.bind((ip, 0))      # leave through this interface
                for dst in _targets(ip):
                    s.sendto(data, (dst, DISCOVERY_PORT))
        except OSError:
            pass
    for index in _interfaces():
        try:
            with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as s:
                s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, index)
                s.sendto(data, ("ff02::1", DISCOVERY_PORT, 0, index))
        except OSError:
            pass


def beacon(port, stop_event, interval=2.0):
    """Announce this laptop until stop_event is set."""
    name = socket.gethostname()
    while not stop_event.is_set():
        _send_beacon(payload(port, name, local_addresses()))
        stop_event.wait(interval)


def answer(port, stop_event, udp_port=DISCOVERY_PORT):
    """Reply to a phone that asks us directly (IPv4 and IPv6 on one socket)."""
    try:
        s = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        s.bind(("::", udp_port))
    except OSError as e:
        print(f"[net] discovery: cannot answer phones on UDP {udp_port} ({e})")
        return
    s.settimeout(1.0)
    name = socket.gethostname()
    with s:
        while not stop_event.is_set():
            try:
                data, addr = s.recvfrom(64)
            except socket.timeout:
                continue
            except OSError:
                continue     # Windows reports an unreachable earlier reply here; keep listening
            if data.startswith(QUESTION):
                try:
                    s.sendto(payload(port, name, local_addresses()), addr)
                except OSError:
                    pass
