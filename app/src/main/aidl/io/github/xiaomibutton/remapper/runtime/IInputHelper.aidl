package io.github.xiaomibutton.remapper.runtime;

import io.github.xiaomibutton.remapper.runtime.IInputClient;

interface IInputHelper {
    void start(boolean intercept, IInputClient client) = 0;
    oneway void heartbeat() = 1;
    oneway void stop() = 2;
    String execute(String command) = 3;
    String openCamera(boolean secure) = 4;
    String openLauncherApp(String packageName) = 5;
    String openSettings(String action) = 6;
    String openWeb(String url) = 7;
    String deviceAction(String id, String value) = 8;
    void destroy() = 16777114;
}
