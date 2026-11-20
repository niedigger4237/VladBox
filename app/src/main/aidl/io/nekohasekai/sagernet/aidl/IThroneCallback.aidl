package io.nekohasekai.sagernet.aidl;

import android.os.ParcelFileDescriptor;

// implemented by the :bg process (it owns the VpnService), called from the :throne process
interface IThroneCallback {
    ParcelFileDescriptor openTun();
    boolean protect(in ParcelFileDescriptor fd);
    oneway void log(int level, String message);
}
