package com.wfdtest.app;

/** Runs inside the Shizuku-managed process (uid 2000, shell) - the only identity that holds
 * CONFIGURE_WIFI_DISPLAY, the signature permission setWfdInfo/setMiracastMode require. */
interface IWfdPrivileged {

    void destroy() = 16777114; // reserved: Shizuku server's own destroy call

    /** Advertises this device as a WFD primary sink and enables Miracast sink mode. Device name
     * and control port match what the (normal, non-privileged) app's own RtspSink listens on. */
    boolean advertiseSink(String deviceName, int controlPort) = 1;
}
