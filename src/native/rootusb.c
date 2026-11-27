// rootusb.c — root USB helper for Moto Flasher BROM (1.1.18).
//
// Why this exists: Android's UsbManager permission is granted per device
// instance. A looping phone re-enumerates its preloader every ~2.5 s as a
// NEW device, so the grant never sticks and the permission dialog loops
// forever (1.1.2 on-device log, 2026-11-08). The fastboot half of the app
// has no such problem: it runs the fastboot binary as root via `su -c`
// and talks to /dev/bus/usb directly. This helper does the same for BROM.
//
// Usage: rootusb /dev/bus/usb/BBB/DDD
//   The helper opens the device node O_RDWR as root and speaks a
//   length-prefixed framing protocol over stdin/stdout. stdin/stdout are
//   BINARY PROTOCOL ONLY — diagnostics go to stderr, never stdout.
//   The helper exits when stdin hits EOF or on a CLOSE command.
//
// Protocol (all multi-byte integers big-endian):
//   Request:  [opcode:1][length:4][payload:length]
//   Response: [status:1 'K'|'E'][length:4][payload:length]
//   Opcodes:
//     'V' version:          no payload. Response K + version string
//         ("rootusb 1.1.18") — the Kotlin side queries this at open so
//         the BROM log is stamped with the helper build, not just the
//         app build (1.1.5; the 1.1.4-vs-1.1.3 ambiguity on 2026-11-08
//         is what this is for).
//     'C' claim interface:  payload = u32 interface number.
//         Response K + empty, or E + error string. The claim runs
//         mtkclient's exact sequence (usblib.py connect): detach ALL
//         interfaces, claim interface 0, detach the data interface
//         again, claim the data interface. A data-interface claim
//         failure carries the per-interface disconnect results
//         ("disconnect d0=ok d1=ENODATA ...") so the log shows WHY.
//     'W' bulk out:         payload = ep(1) + data.
//         Response K + u32 bytes-written, or E + error string.
//     'R' bulk in:          payload = ep(1) + u32 length + u32 timeout_ms.
//         Response K + data bytes read (0..length; 0 on timeout, exactly
//         like one UsbManager bulkTransfer returning 0), or E + error.
//     'T' control transfer: payload = reqType(1) + request(1) + value(2)
//         + index(2) + length(4) + timeout_ms(4) + data.
//         Response K + u32 count + count bytes of data (data only for IN
//         transfers), or E + error string.
//     'X' close:            no payload. No response; the helper exits 0.
//
// usbfs ioctls used (linux/usbdevice_fs.h, libc only, no libusb):
//   USBDEVFS_DISCONNECT (before claim: EVERY interface 0..15, errors
//   recorded for 0..7 — the cdc_acm driver owns the CDC function via
//   interface 0 and only a full disconnect frees the data interface;
//   disconnecting just the claimed interface leaves EBUSY, 1.1.3
//   on-device log) — issued via USBDEVFS_IOCTL with a struct
//   usbdevfs_ioctl, because the direct ioctl has no top-level case in
//   the kernel and returns ENOTTY (1.1.8; torvalds devio.c blob
//   101cb942),
//   USBDEVFS_CLAIMINTERFACE / USBDEVFS_RELEASEINTERFACE,
//   USBDEVFS_BULK, USBDEVFS_CONTROL.
//
// Rebuild (arm64, matches minSdk 28):
//   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang
//     -O2 -Wall -o toolchain/rootusb src/native/rootusb.c
// Host test build (framing + process plumbing only, no real USB):
//   gcc -DROOTUSB_TEST_MAIN -O2 -Wall -o /tmp/rootusb-test src/native/rootusb.c
//   /tmp/rootusb-test
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>

#define MAX_MSG (1024 * 1024)
#define ROOTUSB_VERSION "rootusb 1.1.18"

// ---------------------------------------------------------------------------
// Low-level I/O: full reads/writes with EINTR retry. Raw fds only — no
// stdio buffering on the protocol stream, so a response is on the wire
// the moment write_all returns.
// ---------------------------------------------------------------------------
static int read_exact(int fd, uint8_t *buf, size_t n) {
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, buf + got, n - got);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) return -1; // EOF
        got += (size_t)r;
    }
    return 0;
}

static int write_all(int fd, const uint8_t *buf, size_t n) {
    size_t sent = 0;
    while (sent < n) {
        ssize_t r = write(fd, buf + sent, n - sent);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        sent += (size_t)r;
    }
    return 0;
}

static uint32_t rd_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static void wr_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

// Read one framed request from fd 0. Returns 0 on success, -1 on EOF/error.
// The caller frees *payload_out (NULL when length is 0).
static int msg_read(int fd, uint8_t *op_out, uint8_t **payload_out, uint32_t *len_out) {
    uint8_t hdr[5];
    if (read_exact(fd, hdr, 5) != 0) return -1;
    uint32_t len = rd_be32(hdr + 1);
    if (len > MAX_MSG) return -1;
    uint8_t *payload = NULL;
    if (len > 0) {
        payload = (uint8_t *)malloc(len);
        if (!payload) return -1;
        if (read_exact(fd, payload, len) != 0) {
            free(payload);
            return -1;
        }
    }
    *op_out = hdr[0];
    *payload_out = payload;
    *len_out = len;
    return 0;
}

// Write one framed response to fd 1.
static int msg_write(int fd, uint8_t status, const uint8_t *payload, uint32_t len) {
    uint8_t hdr[5];
    hdr[0] = status;
    wr_be32(hdr + 1, len);
    if (write_all(fd, hdr, 5) != 0) return -1;
    if (len > 0 && write_all(fd, payload, len) != 0) return -1;
    return 0;
}

static int msg_error(int fd, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    return msg_write(fd, 'E', (const uint8_t *)buf, (uint32_t)strlen(buf));
}

// ---------------------------------------------------------------------------
// Helper state.
// ---------------------------------------------------------------------------
static int dev_fd = -1;

// Every interface the helper currently holds (interface 0 AND the data
// interface since 1.1.5, mirroring mtkclient which claims both).
// cleanup() releases all of them.
static int claimed_ifaces[16];
static int claimed_count = 0;

// Short errno name for claim diagnostics ("ok", "ENODATA", "E25").
// The returned pointer for the default case is a single static buffer:
// callers must consume it before the next call.
static const char *errno_name(int e) {
    switch (e) {
        case 0: return "ok";
        case EACCES: return "EACCES";
        case EBUSY: return "EBUSY";
        case ENODEV: return "ENODEV";
        case EINVAL: return "EINVAL";
        case ENODATA: return "ENODATA";
        case ENOTTY: return "ENOTTY";
        case EPIPE: return "EPIPE";
        case ETIMEDOUT: return "ETIMEDOUT";
        case EIO: return "EIO";
        default: {
            static char buf[16];
            snprintf(buf, sizeof(buf), "E%d", e);
            return buf;
        }
    }
}

// Disconnect a kernel driver from one interface. The disconnect MUST go
// through USBDEVFS_IOCTL: the kernel's top-level dispatcher
// (usbdev_do_ioctl, drivers/usb/core/devio.c) has no case for
// USBDEVFS_DISCONNECT, so a direct ioctl returns ENOTTY for a correct
// call (proven by the 1.1.7 on-device log and by torvalds devio.c,
// blob 101cb942). The IOCTL wrapper reaches proc_ioctl, which releases
// the bound driver — or returns ENODATA when nothing is bound, which
// counts as already free. Returns 0 on success / already-free, else the
// errno value.
static int do_disconnect(int iface) {
    struct usbdevfs_ioctl ctrl;
    ctrl.ifno = iface;
    ctrl.ioctl_code = USBDEVFS_DISCONNECT;
    ctrl.data = NULL;
    if (ioctl(dev_fd, USBDEVFS_IOCTL, &ctrl) == 0) return 0;
    int e = errno;
    return e == ENODATA ? 0 : e;
}

static int do_claim(const uint8_t *payload, uint32_t len) {
    if (len != 4) return msg_error(STDOUT_FILENO, "claim: bad length %u", len);
    int iface = (int)rd_be32(payload);
    // mtkclient's exact sequence (usblib.py connect): detach the kernel
    // driver from interface 0, claim interface 0, detach the data
    // interface, claim the data interface. The cdc_acm driver owns the
    // CDC function via interface 0 and claims both interfaces as one
    // driver instance, so claiming the data interface without first
    // detaching 0 fails EBUSY — and disconnecting only the claimed
    // interface (1.1.3) did not free it either. 1.1.4 disconnected ALL
    // interfaces; 1.1.5 keeps that and adds the claim(0) step. A
    // claim(0) failure is ignored (mtkclient passes on it); a
    // data-interface claim failure is fatal and the error carries the
    // per-interface disconnect results so the next log shows WHY the
    // claim failed instead of just "Device or resource busy".
    char diag[384];
    for (int attempt = 0; attempt < 2; attempt++) {
        claimed_count = 0; // disconnect-all below releases any prior claim
        int derr[8];
        for (int i = 0; i < 16; i++) {
            // Disconnect via USBDEVFS_IOCTL (see do_disconnect): the
            // direct ioctl has no top-level case in the kernel and
            // returns ENOTTY, which is what every on-device log showed
            // (1.1.3–1.1.7). The by-value fix in 1.1.7 changed nothing
            // because the call itself was the wrong one, not the arg.
            int rc = do_disconnect(i);
            if (i < 8) derr[i] = rc;
        }
        int pos = snprintf(diag, sizeof(diag),
            "disconnect d0=%s d1=%s d2=%s d3=%s d4=%s d5=%s d6=%s d7=%s",
            errno_name(derr[0]), errno_name(derr[1]),
            errno_name(derr[2]), errno_name(derr[3]),
            errno_name(derr[4]), errno_name(derr[5]),
            errno_name(derr[6]), errno_name(derr[7]));
        if (iface != 0) {
            // Claim interface 0 and hold it for the session (mtkclient
            // holds both); failure is ignored, exactly like usblib.py.
            int zero = 0;
            if (ioctl(dev_fd, USBDEVFS_CLAIMINTERFACE, &zero) == 0) {
                claimed_ifaces[claimed_count++] = 0;
            }
            // Detach the data interface once more, like mtkclient
            // (via the IOCTL path — see do_disconnect).
            int r = do_disconnect(iface);
            if (pos < (int)sizeof(diag) - 1) {
                snprintf(diag + pos, sizeof(diag) - (size_t)pos,
                    "; redetach d%d=%s", iface, errno_name(r));
            }
        }
        if (ioctl(dev_fd, USBDEVFS_CLAIMINTERFACE, &iface) == 0) {
            claimed_ifaces[claimed_count++] = iface;
            return msg_write(STDOUT_FILENO, 'K', NULL, 0);
        }
        int ce = errno;
        if (ce != EBUSY || attempt == 1) {
            return msg_error(STDOUT_FILENO, "claim interface %d: %s [%s]",
                             iface, strerror(ce), diag);
        }
        usleep(100 * 1000);
    }
    return msg_error(STDOUT_FILENO, "claim interface %d: %s [%s]",
                     iface, strerror(EBUSY), diag);
}

static int do_bulk_out(const uint8_t *payload, uint32_t len) {
    if (len < 1) return msg_error(STDOUT_FILENO, "bulk out: empty payload");
    struct usbdevfs_bulktransfer bt;
    memset(&bt, 0, sizeof(bt));
    bt.ep = payload[0];
    bt.len = (int)(len - 1);
    bt.timeout = 5000;
    bt.data = (void *)(payload + 1);
    int ret = ioctl(dev_fd, USBDEVFS_BULK, &bt);
    if (ret < 0) {
        return msg_error(STDOUT_FILENO, "bulk out ep 0x%02x: %s", payload[0], strerror(errno));
    }
    uint8_t out[4];
    wr_be32(out, (uint32_t)ret);
    return msg_write(STDOUT_FILENO, 'K', out, 4);
}

static int do_bulk_in(const uint8_t *payload, uint32_t len) {
    if (len != 9) return msg_error(STDOUT_FILENO, "bulk in: bad length %u", len);
    uint8_t ep = payload[0];
    uint32_t want = rd_be32(payload + 1);
    uint32_t timeout = rd_be32(payload + 5);
    if (want > MAX_MSG) return msg_error(STDOUT_FILENO, "bulk in: length %u too large", want);
    uint8_t *buf = (uint8_t *)malloc(want > 0 ? want : 1);
    if (!buf) return msg_error(STDOUT_FILENO, "bulk in: out of memory");
    struct usbdevfs_bulktransfer bt;
    memset(&bt, 0, sizeof(bt));
    bt.ep = ep;
    bt.len = (int)want;
    bt.timeout = (int)timeout;
    bt.data = buf;
    int ret = ioctl(dev_fd, USBDEVFS_BULK, &bt);
    if (ret < 0) {
        int e = errno;
        free(buf);
        if (e == ETIMEDOUT) {
            // Timeout with 0 bytes is NOT an error: it mirrors one
            // UsbManager bulkTransfer returning 0, so the Kotlin
            // readExact deadline loop behaves exactly as before.
            return msg_write(STDOUT_FILENO, 'K', NULL, 0);
        }
        return msg_error(STDOUT_FILENO, "bulk in ep 0x%02x: %s", ep, strerror(e));
    }
    int rc = msg_write(STDOUT_FILENO, 'K', buf, (uint32_t)ret);
    free(buf);
    return rc;
}

static int do_control(const uint8_t *payload, uint32_t len) {
    if (len < 12) return msg_error(STDOUT_FILENO, "control: bad length %u", len);
    uint8_t req_type = payload[0];
    uint8_t request = payload[1];
    uint16_t value = (uint16_t)((payload[2] << 8) | payload[3]);
    uint16_t index = (uint16_t)((payload[4] << 8) | payload[5]);
    uint32_t wlen = rd_be32(payload + 6);
    uint32_t timeout = rd_be32(payload + 8);
    const uint8_t *data = payload + 12;
    uint32_t data_len = len - 12;
    if (wlen > MAX_MSG) return msg_error(STDOUT_FILENO, "control: length %u too large", wlen);
    uint8_t *buf = (uint8_t *)malloc(wlen > 0 ? wlen : 1);
    if (!buf) return msg_error(STDOUT_FILENO, "control: out of memory");
    if ((req_type & 0x80) == 0 && data_len > 0) {
        memcpy(buf, data, data_len < wlen ? data_len : wlen);
    }
    struct usbdevfs_ctrltransfer ct;
    memset(&ct, 0, sizeof(ct));
    ct.bRequestType = req_type;
    ct.bRequest = request;
    ct.wValue = value;
    ct.wIndex = index;
    ct.wLength = (uint16_t)wlen;
    ct.timeout = (int)timeout;
    ct.data = buf;
    int ret = ioctl(dev_fd, USBDEVFS_CONTROL, &ct);
    if (ret < 0) {
        int e = errno;
        free(buf);
        return msg_error(STDOUT_FILENO, "control: %s", strerror(e));
    }
    // Response: u32 count + count bytes of data (data only for IN).
    uint8_t *resp = (uint8_t *)malloc(4 + (ret > 0 ? (size_t)ret : 0));
    if (!resp) {
        free(buf);
        return msg_error(STDOUT_FILENO, "control: out of memory");
    }
    wr_be32(resp, (uint32_t)ret);
    if (ret > 0) memcpy(resp + 4, buf, (size_t)ret);
    free(buf);
    int rc = msg_write(STDOUT_FILENO, 'K', resp, (uint32_t)(4 + (ret > 0 ? ret : 0)));
    free(resp);
    return rc;
}

static void cleanup(void) {
    for (int i = 0; i < claimed_count; i++) {
        int ifn = claimed_ifaces[i];
        (void)ioctl(dev_fd, USBDEVFS_RELEASEINTERFACE, &ifn);
    }
    claimed_count = 0;
    if (dev_fd >= 0) {
        close(dev_fd);
        dev_fd = -1;
    }
}

static int rootusb_main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: rootusb /dev/bus/usb/BBB/DDD\n");
        return 2;
    }
    signal(SIGPIPE, SIG_IGN);
    dev_fd = open(argv[1], O_RDWR);
    if (dev_fd < 0) {
        fprintf(stderr, "rootusb: cannot open %s: %s\n", argv[1], strerror(errno));
        return 1;
    }
    for (;;) {
        uint8_t op;
        uint8_t *payload = NULL;
        uint32_t len = 0;
        if (msg_read(STDIN_FILENO, &op, &payload, &len) != 0) break; // EOF / error
        int rc = 0;
        switch (op) {
            case 'V': rc = msg_write(STDOUT_FILENO, 'K',
                (const uint8_t *)ROOTUSB_VERSION,
                (uint32_t)strlen(ROOTUSB_VERSION)); break;
            case 'C': rc = do_claim(payload, len); break;
            case 'W': rc = do_bulk_out(payload, len); break;
            case 'R': rc = do_bulk_in(payload, len); break;
            case 'T': rc = do_control(payload, len); break;
            case 'X':
                free(payload);
                cleanup();
                return 0;
            default:
                rc = msg_error(STDOUT_FILENO, "unknown opcode 0x%02x", op);
                break;
        }
        free(payload);
        if (rc != 0) break; // stdout broke; nothing more can be said
    }
    cleanup();
    return 0;
}

#ifdef ROOTUSB_TEST_MAIN
// ---------------------------------------------------------------------------
// Host test main: framing codec + process plumbing only. There is no USB
// device on the build VM, so this proves the codec, the command dispatch
// and the error paths — NOT the USB path. The USB path is proven only by
// on-device runs.
// ---------------------------------------------------------------------------
static int failures = 0;
static void check(int cond, const char *what) {
    if (!cond) {
        fprintf(stderr, "FAIL: %s\n", what);
        failures++;
    } else {
        printf("ok: %s\n", what);
    }
}

static int test_framing_roundtrip(void) {
    // A pipe only holds ~64 KB, so a 1 MB message written by the parent
    // with nobody reading deadlocks the writer. The echo child reads
    // while the parent writes, the same way the real helper and the
    // Kotlin transport talk to each other.
    int to_child[2], from_child[2];
    if (pipe(to_child) != 0 || pipe(from_child) != 0) {
        check(0, "pipes for framing test");
        return -1;
    }
    pid_t pid = fork();
    if (pid == 0) {
        close(to_child[1]);
        close(from_child[0]);
        for (;;) {
            uint8_t op = 0;
            uint8_t *out = NULL;
            uint32_t olen = 0;
            if (msg_read(to_child[0], &op, &out, &olen) != 0) _exit(0);
            if (msg_write(from_child[1], op, out, olen) != 0) _exit(1);
            free(out);
        }
    }
    close(to_child[0]);
    close(from_child[1]);
    // Messages of 0, 1, 512 and 65536 bytes, plus a max-size boundary.
    static const uint32_t sizes[] = {0, 1, 512, 65536, MAX_MSG};
    for (size_t i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
        uint32_t n = sizes[i];
        uint8_t *buf = (uint8_t *)malloc(n > 0 ? n : 1);
        for (uint32_t j = 0; j < n; j++) buf[j] = (uint8_t)(j * 31 + i);
        if (msg_write(to_child[1], 'K', buf, n) != 0) { check(0, "framing write"); free(buf); return -1; }
        uint8_t op = 0;
        uint8_t *out = NULL;
        uint32_t olen = 0;
        if (msg_read(from_child[0], &op, &out, &olen) != 0) { check(0, "framing read"); free(buf); return -1; }
        check(op == 'K' && olen == n && (n == 0 || memcmp(out, buf, n) == 0),
              "framing round-trip");
        free(buf);
        free(out);
    }
    close(to_child[1]);
    close(from_child[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    check(WIFEXITED(status) && WEXITSTATUS(status) == 0, "echo child exits cleanly on EOF");
    // Truncated message: length says 10, only 3 bytes follow, then EOF.
    int fds[2];
    if (pipe(fds) != 0) { check(0, "pipe for truncation test"); return -1; }
    uint8_t hdr[5] = {'R', 0, 0, 0, 10};
    if (write_all(fds[1], hdr, 5) != 0 || write_all(fds[1], (const uint8_t *)"abc", 3) != 0) {
        check(0, "truncation setup");
        return -1;
    }
    close(fds[1]);
    uint8_t op = 0;
    uint8_t *out = NULL;
    uint32_t olen = 0;
    check(msg_read(fds[0], &op, &out, &olen) != 0, "truncated message rejected");
    close(fds[0]);
    return 0;
}

static int test_startup_failure(void) {
    // Fork the real helper against a node that does not exist: it must
    // exit nonzero with a message on stderr.
    pid_t pid = fork();
    if (pid == 0) {
        int nullfd = open("/dev/null", O_WRONLY);
        if (nullfd >= 0) { dup2(nullfd, STDOUT_FILENO); }
        char *args[] = {(char *)"rootusb", (char *)"/dev/bus/usb/999/999", NULL};
        _exit(rootusb_main(2, args));
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) { check(0, "waitpid startup-failure child"); return -1; }
    check(WIFEXITED(status) && WEXITSTATUS(status) != 0,
          "missing device node exits nonzero");
    return 0;
}

static int test_dispatch_over_pipes(void) {
    // Fork the real helper against /dev/null (opens fine, is not a USB
    // device): every USB op must come back as a framed 'E', and 'X'
    // must exit the helper cleanly. This drives the real command
    // dispatch + framing over pipes end to end.
    int to_child[2], from_child[2];
    if (pipe(to_child) != 0 || pipe(from_child) != 0) {
        check(0, "pipes for dispatch test");
        return -1;
    }
    pid_t pid = fork();
    if (pid == 0) {
        dup2(to_child[0], STDIN_FILENO);
        dup2(from_child[1], STDOUT_FILENO);
        close(to_child[0]); close(to_child[1]);
        close(from_child[0]); close(from_child[1]);
        char *args[] = {(char *)"rootusb", (char *)"/dev/null", NULL};
        _exit(rootusb_main(2, args));
    }
    close(to_child[0]);
    close(from_child[1]);
    int wfd = to_child[1], rfd = from_child[0];
    int ok = 1;
    // 'C' claim -> E (ENOTTY on /dev/null), and the error carries the
    // per-interface disconnect diagnostics (1.1.5).
    uint8_t claim[4] = {0, 0, 0, 0};
    ok &= (msg_write(wfd, 'C', claim, 4) == 0);
    uint8_t op = 0; uint8_t *out = NULL; uint32_t olen = 0;
    ok &= (msg_read(rfd, &op, &out, &olen) == 0);
    check(ok && op == 'E' && olen > 0, "claim on non-USB node returns framed error");
    {
        char tmp[600];
        size_t n = olen < sizeof(tmp) - 1 ? olen : sizeof(tmp) - 1;
        memcpy(tmp, out, n);
        tmp[n] = 0;
        check(strstr(tmp, "disconnect d0=") != NULL,
              "claim error carries disconnect diagnostics");
    }
    free(out); out = NULL;
    // 'V' version -> K + "rootusb 1.1.18".
    ok = (msg_write(wfd, 'V', NULL, 0) == 0);
    ok &= (msg_read(rfd, &op, &out, &olen) == 0);
    check(ok && op == 'K' && olen == strlen(ROOTUSB_VERSION) &&
          memcmp(out, ROOTUSB_VERSION, olen) == 0,
          "version opcode returns helper version");
    free(out); out = NULL;
    // Second 'C' claim (iface 1: the mtkclient sequence claims 0 then
    // the data interface) -> E as well, no hang.
    uint8_t claim1[4] = {0, 0, 0, 1};
    ok = (msg_write(wfd, 'C', claim1, 4) == 0);
    ok &= (msg_read(rfd, &op, &out, &olen) == 0);
    check(ok && op == 'E' && olen > 0, "second claim (data iface) returns framed error");
    free(out); out = NULL;
    // 'R' bulk in -> E.
    uint8_t bulk_in[9] = {0x81, 0, 0, 0, 64, 0, 0, 1, 0xF4}; // ep 0x81, len 64, timeout 500
    ok = (msg_write(wfd, 'R', bulk_in, 9) == 0);
    ok &= (msg_read(rfd, &op, &out, &olen) == 0);
    check(ok && op == 'E', "bulk in on non-USB node returns framed error");
    free(out); out = NULL;
    // 'W' bulk out -> E.
    uint8_t bulk_out[5] = {0x01, 0xA0, 0x0A, 0x50, 0x05};
    ok = (msg_write(wfd, 'W', bulk_out, 5) == 0);
    ok &= (msg_read(rfd, &op, &out, &olen) == 0);
    check(ok && op == 'E', "bulk out on non-USB node returns framed error");
    free(out); out = NULL;
    // 'T' control -> E.
    uint8_t ctrl[12] = {0x21, 0x20, 0, 0, 0, 0, 0, 0, 0, 7, 0, 0};
    ctrl[9] = 0; ctrl[10] = 0x03; ctrl[11] = 0xE8; // timeout 1000
    ok = (msg_write(wfd, 'T', ctrl, 12) == 0);
    ok &= (msg_read(rfd, &op, &out, &olen) == 0);
    check(ok && op == 'E', "control on non-USB node returns framed error");
    free(out); out = NULL;
    // Unknown opcode -> E.
    ok = (msg_write(wfd, 'Z', NULL, 0) == 0);
    ok &= (msg_read(rfd, &op, &out, &olen) == 0);
    check(ok && op == 'E', "unknown opcode returns framed error");
    free(out); out = NULL;
    // 'X' -> helper exits 0, no response.
    ok = (msg_write(wfd, 'X', NULL, 0) == 0);
    close(wfd);
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) { check(0, "waitpid dispatch child"); return -1; }
    check(WIFEXITED(status) && WEXITSTATUS(status) == 0, "CLOSE exits helper cleanly");
    close(rfd);
    return 0;
}

static int test_main(void) {
    test_framing_roundtrip();
    test_startup_failure();
    test_dispatch_over_pipes();
    if (failures == 0) {
        printf("ALL ROOTUSB HOST TESTS PASSED\n");
        return 0;
    }
    fprintf(stderr, "%d ROOTUSB HOST TEST(S) FAILED\n", failures);
    return 1;
}
#endif

int main(int argc, char **argv) {
#ifdef ROOTUSB_TEST_MAIN
    (void)argc; (void)argv;
    return test_main();
#else
    return rootusb_main(argc, argv);
#endif
}
