package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.IThroneCallback;

// Throne's Go runtime lives in its own process: two Go runtimes in one process crash each other
interface IThroneService {
    // returns "" on success, an error message otherwise
    String start(String config, IThroneCallback cb);
    void close();
    void pause();
    void wake();
    void resetNetwork();
    boolean selectOutbound(String group, String tag);
    long queryStats(String tag, String direction);
}
