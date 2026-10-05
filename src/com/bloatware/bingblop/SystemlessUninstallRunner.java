package com.bloatware.bingblop;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for a standalone {@code app_process} run under whatever identity is already driving this app's
 * privileged shell (ADB/Shizuku shell UID 2000, or root) - never under this app's own UID, since app_process is
 * started by {@code executeShell} the same way a plain {@code pm}/{@code am} command would be. It calls the
 * hidden {@code IPackageManager.deletePackageAsUser} directly over Binder with the DELETE_SYSTEM_APP flag set -
 * the one bit the {@code pm uninstall} command line never exposes - which is what lets a system app actually be
 * removed for one user without root. This is the same technique App Manager and Canta use for the same refusal;
 * it only ever runs as a fallback once a plain {@code pm uninstall --user 0} has already come back with
 * Android's "only root can delete system app for a particular user" line, so the DELETE_SYSTEM_APP flag below is
 * always correct to pass - that refusal only happens for a system app in the first place.
 *
 * The exact {@code IPackageManager.deletePackageAsUser(...)} parameter list has changed shape release to
 * release (an int versionCode was added in API 26; some branches use a long), so nothing here names it at
 * compile time. It is read reflectively off the live {@code IPackageManager} the device actually has, and its
 * arguments are filled in by position: the package name goes into the one String parameter; every int/long
 * before the callback parameter is treated as a "version" and set to -1 (any installed version); every
 * int/long after the callback is, in order, userId then the delete flags - a shape that has held for this
 * method across every API level since 26 (this app's own minSdkVersion). The callback itself is implemented
 * with a real {@link Binder} subclass, not a {@link Proxy} - a plain dynamic proxy has no native Binder behind
 * it, so a remote call back into one (exactly what the system does to report the result here) can never be
 * delivered; it would silently never arrive, leaving this process to time out even though the deletion itself
 * went through. The {@link Proxy} that gets passed to {@code deletePackageAsUser} only exists to satisfy the
 * Java type system for the reflective call (the parameter's declared type is the hidden IPackageDeleteObserver
 * / IPackageDeleteObserver2 interface, which cannot be implemented directly without compiling against it); the
 * one method it actually answers locally is {@code asBinder()}, where it hands back the real Binder. Both
 * observer interfaces are plain single-method callbacks shaped the same way for this purpose - (String, int,
 * ...) - so the real Binder's onTransact reads the package name, then the one int result code, and ignores
 * anything after it.
 */
public final class SystemlessUninstallRunner {

    // PackageManager.DELETE_SYSTEM_APP - a real, stable AOSP flag bit, but marked @SystemApi so it is not in
    // the public android.jar this app compiles against; the literal is the only way to reference it here.
    private static final int DELETE_SYSTEM_APP = 0x00000004;
    private static final int DELETE_SUCCEEDED = 1;
    private static final long TIMEOUT_MS = 15000;

    private SystemlessUninstallRunner() {}

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("RESULT:ERROR:missing package name or user id");
            return;
        }
        String pkg = args[0];
        int userId;
        try {
            userId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("RESULT:ERROR:bad user id " + args[1]);
            return;
        }
        try {
            run(pkg, userId);
        } catch (Throwable t) {
            System.out.println("RESULT:ERROR:" + t);
        }
    }

    private static void run(String pkg, int userId) throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Object pmBinder = serviceManagerClass.getMethod("getService", String.class).invoke(null, "package");
        if (pmBinder == null) {
            System.out.println("RESULT:ERROR:ServiceManager has no 'package' service");
            return;
        }

        Class<?> ibinderClass = Class.forName("android.os.IBinder");
        Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
        Object ipm = stubClass.getMethod("asInterface", ibinderClass).invoke(null, pmBinder);
        Class<?> ipmIface = Class.forName("android.content.pm.IPackageManager");

        Method deleteMethod = null;
        for (Method m : ipmIface.getMethods()) {
            if (m.getName().equals("deletePackageAsUser")) {
                deleteMethod = m;
                break;
            }
        }
        if (deleteMethod == null) {
            System.out.println("RESULT:ERROR:IPackageManager.deletePackageAsUser not found on this device");
            return;
        }

        Class<?>[] paramTypes = deleteMethod.getParameterTypes();
        int observerIndex = -1;
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i].isInterface() && !paramTypes[i].getName().startsWith("java.")) {
                observerIndex = i;
                break;
            }
        }
        if (observerIndex < 0) {
            System.out.println("RESULT:ERROR:deletePackageAsUser has no callback parameter on this device");
            return;
        }

        final int[] resultCode = { Integer.MIN_VALUE };
        final CountDownLatch latch = new CountDownLatch(1);

        Object[] callArgs = new Object[paramTypes.length];
        int intsAfterObserver = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> t = paramTypes[i];
            if (t == String.class) {
                callArgs[i] = pkg;
            } else if (i == observerIndex) {
                callArgs[i] = observerProxy(t, observerBinder(t.getName(), resultCode, latch));
            } else if (t == int.class || t == long.class) {
                if (i < observerIndex) {
                    // Before the callback: a "version code" parameter - -1 means accept whatever is installed.
                    callArgs[i] = (t == long.class) ? (Object) Long.valueOf(-1L) : (Object) Integer.valueOf(-1);
                } else {
                    // After the callback, in order: userId, then flags, then anything further defaults to 0.
                    int which = intsAfterObserver++;
                    int value = which == 0 ? userId : (which == 1 ? DELETE_SYSTEM_APP : 0);
                    callArgs[i] = (t == long.class) ? (Object) Long.valueOf(value) : (Object) Integer.valueOf(value);
                }
            } else {
                callArgs[i] = null; // e.g. an IIntentSender parameter on some branches - not needed for this call
            }
        }

        deleteMethod.invoke(ipm, callArgs);

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            System.out.println("RESULT:ERROR:timed out waiting for the uninstall callback");
            return;
        }
        if (resultCode[0] == DELETE_SUCCEEDED) {
            System.out.println("RESULT:OK");
        } else {
            System.out.println("RESULT:FAIL:" + resultCode[0]);
        }
    }

    /** The real, native-backed Binder the system actually calls back into. Its onTransact is what runs for
     *  the packageDeleted/onPackageDeleted callback - never the {@link Proxy}'s InvocationHandler, since an
     *  incoming Binder transaction is dispatched to whatever concrete IBinder {@code asBinder()} handed out,
     *  bypassing Java interface dispatch entirely. */
    private static Binder observerBinder(final String descriptor, final int[] resultCode, final CountDownLatch latch) {
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                try {
                    data.enforceInterface(descriptor);
                    data.readString();              // the package name - not needed here
                    resultCode[0] = data.readInt();  // packageDeleted(String,int) / onPackageDeleted(String,int,String): the int is always second
                } catch (Throwable ignored) {
                    // leave resultCode at its sentinel; still release the latch below so this never hangs
                } finally {
                    if (reply != null && (flags & IBinder.FLAG_ONEWAY) == 0) reply.writeNoException();
                    latch.countDown();
                }
                return true;
            }
        };
    }

    /** A type witness only: {@code deletePackageAsUser}'s reflected parameter type is the hidden
     *  IPackageDeleteObserver/IPackageDeleteObserver2 interface, which cannot be implemented directly without
     *  compiling against it, so {@link Method#invoke} needs a {@link Proxy} of that exact interface to accept
     *  the argument at all. The only method actually called on it locally is {@code asBinder()} (by the AIDL
     *  marshalling code, to get the real IBinder to write into the outgoing Parcel) - that is the one method
     *  answered here, with the real {@link #observerBinder}; nothing else is ever invoked on this object. */
    private static Object observerProxy(Class<?> iface, final Binder realBinder) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface }, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] methodArgs) {
                if ("asBinder".equals(method.getName())) return realBinder;
                return null;
            }
        });
    }
}
