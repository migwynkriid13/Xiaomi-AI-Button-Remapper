#include "watchdog.hpp"
#include <cerrno>
#include <csignal>
#include <iostream>
#include <stdexcept>
#include <sys/wait.h>
#include <unistd.h>

int main() {
    // A real process is stopped with all threads suspended. Its guardian must
    // release its kernel resources by terminating that exact process.
    int status = 0;
    pid_t helper = fork();
    if (helper < 0) return 1;
    if (helper == 0) {
        try {
            Watchdog watchdog;
            watchdog.arm();
            watchdog.beat();
            raise(SIGSTOP);
            _exit(10);
        } catch (const std::exception& e) {
            std::cerr << e.what() << '\n';
            _exit(11);
        }
    }
    pid_t result;
    do { result = waitpid(helper, &status, 0); } while (result < 0 && errno == EINTR);
    if (result != helper || !WIFSIGNALED(status) || WTERMSIG(status) != SIGKILL) return 1;

    // Normal shutdown must never kill the caller after disarming.
    Watchdog normal;
    normal.arm(); normal.beat();
    std::cout << "Frozen helper terminated, responsive helper disarmed normally.\n";
    return 0;
}
