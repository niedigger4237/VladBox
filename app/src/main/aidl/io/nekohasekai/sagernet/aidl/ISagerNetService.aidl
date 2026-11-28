package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  // -1 rejected, 0 unmanaged group, 1 switched, 2 service restart requested.
  int selectManagedOutbound(long sessionId, String group, String tag);
  long getSelectorSessionId();
  // Empty when the running member cannot be verified for a read-only probe.
  String getSelectorProbeTag(long sessionId, long profileId);
}
