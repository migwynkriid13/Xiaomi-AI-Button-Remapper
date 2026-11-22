#include "watchdog.hpp"

#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <stdexcept>
#include <string>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

namespace {

long long monotonicMs() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return static_cast<long long>(t.tv_sec) * 1000 + t.tv_nsec / 1000000;
}

// After fork in a multithreaded ART process, use only direct system calls
// and async-signal-safe operations. No allocation, JVM, or logging here.
[[noreturn]] void guardParent(int socketFd, int parentFd, pid_t parent, rlim_t limit) {
    // Use fixed, non-overlapping descriptor numbers. The parent duplicated
    // both arguments above fd 4 before fork, so neither dup2 can clobber one.
    if (dup2(socketFd, 3) < 0 || dup2(parentFd, 4) < 0) _exit(1);
    close(0); close(1); close(2);
#ifdef __NR_close_range
    if (syscall(__NR_close_range, 5u, ~0u, 0) < 0)
#endif
    {
        for (rlim_t i = 5; i < limit; ++i) close(static_cast<int>(i));
    }
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent ||
        syscall(__NR_pidfd_send_signal, 4, 0, nullptr, 0) < 0) _exit(1);
    bool armed = false;
    long long deadline = 0;
    for (;;) {
        int timeout = -1;
        if (armed) {
            const long long remaining = deadline - monotonicMs();
            if (remaining <= 0) {
                // A pidfd explicitly targets the original JVM even across PID reuse.
                syscall(__NR_pidfd_send_signal, 4, SIGKILL, nullptr, 0);
                _exit(0);
            }
            timeout = static_cast<int>(remaining);
        }
        pollfd p{3, POLLIN, 0};
        const int ready = poll(&p, 1, timeout);
        if (ready == 0 || (ready < 0 && errno == EINTR)) continue;
        char code;
        if (ready < 0 || read(3, &code, 1) != 1) _exit(0);
        if (code == 'A') {
            armed = true;
            const char acknowledged = 'Y';
            if (write(3, &acknowledged, 1) != 1) _exit(1);
        } else if (code != 'H' || !armed) {
            _exit(1);
        }
        deadline = monotonicMs() + 3500;
    }
}

[[noreturn]] void unavailable(int err) {
    throw std::runtime_error(std::string("Independent input watchdog unavailable: ") + strerror(err));
}

} // namespace

Watchdog::Watchdog() {
    const pid_t parent = getpid();
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) < 0)
        unavailable(errno);
    const int pin = static_cast<int>(syscall(__NR_pidfd_open, parent, 0));
    const int pinError = errno;
    const int childPin = pin < 0 ? -1 : fcntl(pin, F_DUPFD_CLOEXEC, 5);
    int err = pin < 0 ? pinError : errno;
    const int childSocket = fcntl(pair[1], F_DUPFD_CLOEXEC, 5);
    if (childSocket < 0) err = errno;
    rlimit limit{};
    if (childSocket >= 0 && childPin >= 0 && getrlimit(RLIMIT_NOFILE, &limit) == 0) {
        child_ = fork();
        if (child_ == 0) guardParent(childSocket, childPin, parent, limit.rlim_cur);
        if (child_ < 0) err = errno;
    }
    if (pin >= 0) close(pin);
    if (childPin >= 0) close(childPin);
    if (childSocket >= 0) close(childSocket);
    close(pair[1]);
    if (child_ < 0) { close(pair[0]); unavailable(err); }
    socket_ = pair[0];
}

Watchdog::~Watchdog() {
    close(socket_);
    // Reap the child. The grab guard is already destroyed on every exit path.
    // Wait with WNOHANG first so an external SIGSTOP of the child cannot hang shutdown.
    int status;
    pid_t waited;
    do { waited = waitpid(child_, &status, WNOHANG); } while (waited < 0 && errno == EINTR);
    if (waited == 0) {
        kill(child_, SIGKILL);
        while (waitpid(child_, &status, 0) < 0 && errno == EINTR) {}
    }
}

void Watchdog::arm() {
    const char arm = 'A';
    if (send(socket_, &arm, 1, MSG_NOSIGNAL) != 1) unavailable(errno);
    pollfd p{socket_, POLLIN, 0};
    char reply = 0;
    if (poll(&p, 1, 1500) != 1 || read(socket_, &reply, 1) != 1 || reply != 'Y')
        throw std::runtime_error("Independent watchdog could not acknowledge the session.");
}

void Watchdog::beat() {
    const auto now = monotonicMs();
    if (now < nextBeat_) return;
    const char heartbeat = 'H';
    if (send(socket_, &heartbeat, 1, MSG_NOSIGNAL) != 1) unavailable(errno);
    nextBeat_ = now + 500;
}

