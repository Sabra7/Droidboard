"""Wi-Fi pairing: a 6-digit code (kept in pairing.txt), the laptop's LAN addresses, and the listening sockets."""
import secrets
import socket
from pathlib import Path

import paths


HELLO_MAGIC = b"PMHI"
HELLO_SIZE = len(HELLO_MAGIC) + 6
CODE_FILE = Path(paths.data("pairing.txt"))


def pair_code():
    try:
        code = CODE_FILE.read_text().strip()
        if len(code) == 6 and code.isdigit():
            return code
    except OSError:
        pass
    code = f"{secrets.randbelow(10 ** 6):06d}"
    CODE_FILE.write_text(code)
    return code


def check_hello(data, code):
    return data == HELLO_MAGIC + code.encode()


def is_local(addr):
    """A connection from this PC (USB through adb reverse). A dual-stack server sees IPv4 as ::ffff:a.b.c.d."""
    return peer_host(addr) in ("127.0.0.1", "::1")


def peer_host(addr):
    """The address as people write it: ::ffff:192.168.1.5 -> 192.168.1.5."""
    return addr[7:] if addr.startswith("::ffff:") else addr


def listen_socket(host, port, exclusive=False):
    """A bound TCP socket. On "0.0.0.0" (Wi-Fi) it takes IPv4 and IPv6 alike, because a Wi-Fi without DHCP
    leaves the laptop with only IPv6 that works. `exclusive`: a second copy of the program fails to bind."""
    wildcard = host in ("0.0.0.0", "::")
    s = socket.socket(socket.AF_INET6 if wildcard else socket.AF_INET, socket.SOCK_STREAM)
    if exclusive and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    else:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if wildcard:
        s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
    try:
        s.bind(("::" if wildcard else host, port))
    except OSError:
        s.close()
        raise
    return s


def local_ips():
    """Non-loopback IPv4 addresses of this machine."""
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips


def local_addresses():
    """Every address a phone might reach this laptop on, best first: IPv4 from DHCP, then IPv6,
    then 169.254 (Windows makes that up when DHCP fails; it rarely works)."""
    v4 = local_ips()
    v6 = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET6):
            ip = info[4][0].split("%")[0]
            if ip != "::1" and not ip.lower().startswith("fe80") and ip not in v6:
                v6.append(ip)
    except OSError:
        pass
    good = [ip for ip in v4 if not ip.startswith("169.254.")]
    made_up = [ip for ip in v4 if ip.startswith("169.254.")]
    return good + v6 + made_up
