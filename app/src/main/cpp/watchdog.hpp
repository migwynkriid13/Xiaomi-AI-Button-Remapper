#pragma once

// A dedicated child observes progress even if every thread of the Shizuku JVM
// is stopped. It opens no input device and holds no input file description.
class Watchdog {
public:
    Watchdog();
    ~Watchdog();
    Watchdog(const Watchdog&) = delete;
    Watchdog& operator=(const Watchdog&) = delete;
    void arm();
    void disarm();
    void beat();
private:
    int socket_ = -1;
    int child_ = -1;
    int childPin_ = -1;
    long long nextBeat_ = 0;
};
