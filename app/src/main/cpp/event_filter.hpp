#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace remap {

// Linux input-event-codes.h values, also used by the platform-independent replay tests.
constexpr int kSync = 0, kKey = 1, kMisc = 4;
constexpr int kReport = 0, kDropped = 3, kScan = 4;
constexpr int kAiKey = 0x2b1;

enum class FrameResult { Pending, Ready, LostSync };

// Evdev reads may split a frame. Hold it until SYN_REPORT so an MSC_SCAN belonging
// to the AI key cannot become the scan/usage for the following power or volume key.
template<class Event>
class Frame {
public:
    FrameResult append(const Event& event) {
        if ((event.type == kSync && event.code == kDropped) || count_ == events_.size()) {
            return FrameResult::LostSync;
        }
        events_[count_++] = event;
        return event.type == kSync && event.code == kReport ? FrameResult::Ready : FrameResult::Pending;
    }

    template<class Send, class Notify>
    bool deliver(bool intercept, Send send, Notify notify) {
        std::array<bool, 256> skip{};
        size_t scan = count_;
        for (size_t i = 0; i < count_; ++i) {
            const Event& event = events_[i];
            if (event.type == kMisc && event.code == kScan) scan = i;
            if (event.type != kKey) continue;
            if (event.code == kAiKey) {
                skip[i] = true;
                if (scan < count_) skip[scan] = true;
            }
            scan = count_;
        }

        // Send the frame to Android before running app callbacks. SYN_REPORT stays
        // in place even in an AI-only frame. Observation never writes a mirror.
        if (intercept) {
            for (size_t i = 0; i < count_; ++i) {
                if (!skip[i] && !send(events_[i])) return false;
            }
        }
        for (size_t i = 0; i < count_; ++i) {
            const Event& event = events_[i];
            if (event.type == kKey && event.code == kAiKey &&
                event.value >= 0 && event.value <= 2 && !notify(event)) return false;
        }
        count_ = 0;
        return true;
    }

    bool incomplete() const { return count_ != 0; }
private:
    std::array<Event, 256> events_{};
    size_t count_ = 0;
};

class EmergencyHold {
public:
    void key(int value, int64_t eventTime) {
        if (value == 1 && down_ < 0) down_ = eventTime;
        if (value == 0) down_ = -1;
    }
    bool expired(int64_t now) const { return down_ >= 0 && now - down_ >= 8000; }
private:
    int64_t down_ = -1;
};

template<size_t Count>
class ForwardedKeys {
public:
    template<class Event>
    void written(const Event& event) {
        if (event.type == kKey && event.code < Count && event.value != 2)
            down_[event.code] = event.value != 0;
    }
    template<class F>
    void releaseHeld(F release) {
        for (size_t code = 0; code < Count; ++code)
            if (down_[code]) release(code);
    }
private:
    std::array<bool, Count> down_{};
};

} // namespace remap
