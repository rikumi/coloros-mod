package com.rikumi.colorosmod;

import android.os.IBinder;
import android.os.Parcel;
import java.io.IOException;

/** Query the currently registered WiFi service; no persisted PID or /proc visibility is required. */
public final class WifiBssidReadiness {
    public static final int TRANSACTION = 0x00F0434D;
    public static final int CONNECT_ONCE = TRANSACTION + 1;
    public static final int VERSION = 1;
    public static final String DESCRIPTOR = "com.rikumi.colorosmod.WifiBssidFilter";

    private static IBinder service() throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "wifi");
    }
    public static void connectOnce(String profile, String bssid) throws Exception {
        IBinder service = service();
        if (service == null) throw new IOException("WiFi service unavailable");
        Parcel request = Parcel.obtain(), response = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeString(profile); request.writeString(bssid);
            if (!service.transact(CONNECT_ONCE, request, response, 0))
                throw new IOException("WiFi service has no active BSSID connection handler");
            response.readException();
            if (response.readInt() != VERSION) throw new IOException("WiFi BSSID filter protocol mismatch");
        } finally { request.recycle(); response.recycle(); }
    }

    public static void requireReady() throws Exception {
        IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "wifi");
        requireReady(service);
    }

    public static void requireReady(IBinder service) throws Exception {
        if (service == null) throw new IOException("WiFi service unavailable");
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(TRANSACTION, request, response, 0))
                throw new IOException("WiFi service has no active BSSID filter");
            response.readException();
            if (response.readInt() != VERSION) throw new IOException("WiFi BSSID filter protocol mismatch");
        } finally {
            request.recycle();
            response.recycle();
        }
    }
    private WifiBssidReadiness() {}
}
