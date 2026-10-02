package com.moulberry.flashback.exporting.taskbar;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.MobileCompat;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;

public class TaskbarHost {
    // NOTE: this class deliberately references NO com.sun.jna / Win32 / GLFW-native types.
    // All JNA usage lives behind createWindowsInterface() via reflection so that ART
    // verification on Android (Pojav/Mojo) can never fail with NoClassDefFoundError.
    // Only called on Windows desktops.
    public static ITaskbar createTaskbar() {
        // Check Android first, before any platform sniffing that could pull in desktop-only classes.
        if (MobileCompat.isAndroid()) {
            return new NoopTaskbar();
        }
        if (isWindows()) {
            try {
                return createWindowsInterface();
            } catch (Exception | LinkageError e) {
                Flashback.LOGGER.error("Unable to create windows taskbar interface", e);
                return new NoopTaskbar();
            }
        } else {
            return new NoopTaskbar();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static ITaskbar createWindowsInterface() throws Exception {
        ClassLoader loader = TaskbarHost.class.getClassLoader();

        // new Guid.GUID("56FDF344-FD6D-11d0-958A-006097C9A090") (CLSID_TaskbarList)
        Class<?> guidClass = Class.forName("com.sun.jna.platform.win32.Guid$GUID", true, loader);
        Constructor<?> guidCtor = guidClass.getConstructor(String.class);
        Object clsid = guidCtor.newInstance("56FDF344-FD6D-11d0-958A-006097C9A090");
        Object iid = guidCtor.newInstance("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF");

        // WTypes.CLSCTX_SERVER
        Class<?> wtypesClass = Class.forName("com.sun.jna.platform.win32.WTypes", true, loader);
        int clsctxServer = wtypesClass.getField("CLSCTX_SERVER").getInt(null);

        // new PointerByReference()
        Class<?> pbrClass = Class.forName("com.sun.jna.ptr.PointerByReference", true, loader);
        Object itaskbar3res = pbrClass.getConstructor().newInstance();

        // Ole32.INSTANCE.CoCreateInstance(clsid, null, clsctxServer, iid, itaskbar3res)
        Class<?> ole32Class = Class.forName("com.sun.jna.platform.win32.Ole32", true, loader);
        Object ole32 = ole32Class.getField("INSTANCE").get(null);
        Method coCreateInstance = null;
        for (Method method : ole32Class.getMethods()) {
            if (method.getName().equals("CoCreateInstance") && method.getParameterCount() == 5) {
                coCreateInstance = method;
                break;
            }
        }
        if (coCreateInstance == null) {
            throw new IllegalStateException("Ole32.CoCreateInstance not found");
        }
        Object hresult = coCreateInstance.invoke(ole32, clsid, null, clsctxServer, iid, itaskbar3res);

        // W32Errors.FAILED(hresult)
        Class<?> w32ErrorsClass = Class.forName("com.sun.jna.platform.win32.W32Errors", true, loader);
        boolean failed = (boolean) w32ErrorsClass.getMethod("FAILED", hresult.getClass()).invoke(null, hresult);
        if (failed) {
            throw new IllegalStateException("Failed to create ITaskbar3");
        }
        Object taskbarPtr = pbrClass.getMethod("getValue").invoke(itaskbar3res);

        // new WinDef.HWND(new Pointer(GLFWNativeWin32.glfwGetWin32Window(window.handle())))
        Class<?> glfwNativeWin32 = Class.forName("org.lwjgl.glfw.GLFWNativeWin32", true, loader);
        long hwndValue = (long) glfwNativeWin32.getMethod("glfwGetWin32Window", long.class)
            .invoke(null, Minecraft.getInstance().getWindow().handle());

        Class<?> pointerClass = Class.forName("com.sun.jna.Pointer", true, loader);
        Object nativePtr = pointerClass.getConstructor(long.class).newInstance(hwndValue);

        Class<?> hwndClass = Class.forName("com.sun.jna.platform.win32.WinDef$HWND", true, loader);
        Object hwnd = hwndClass.getConstructor(pointerClass).newInstance(nativePtr);

        // new WindowsTaskbar(ptr, hwnd) — loaded reflectively so this class never links against JNA
        Class<?> taskbarClass = Class.forName("com.moulberry.flashback.exporting.taskbar.WindowsTaskbar", true, loader);
        Constructor<?> ctor = taskbarClass.getDeclaredConstructor(pointerClass, hwndClass);
        ctor.setAccessible(true);
        return (ITaskbar) ctor.newInstance(taskbarPtr, hwnd);
    }
}
