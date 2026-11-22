package io.github.xiaomibutton.remapper.runtime;

oneway interface IInputClient {
    void status(String message);
    void key(int value, long uptimeMillis);
    void ended(String reason);
    void pulse();
}
