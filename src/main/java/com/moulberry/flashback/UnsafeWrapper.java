package com.moulberry.flashback;

import org.lwjgl.system.Pointer;
import org.lwjgl.system.jni.JNINativeInterface;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.LongPredicate;

public class UnsafeWrapper {

    /**
     * May be null on platforms where sun.misc.Unsafe is unavailable (e.g. Android hidden-API
     * restrictions on Pojav/Mojo). Never throws; callers must check {@link #isAvailable()}.
     */
    public static final Unsafe UNSAFE;

    static {
        UNSAFE = getUnsafeInstance();
    }

    public static boolean isAvailable() {
        return UNSAFE != null;
    }

    private static Unsafe getUnsafeInstance() {
        // Try getting theUnsafe
        try {
            final Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (Unsafe) theUnsafe.get(null);
        } catch (Exception ignored) {}

        // Try searching for fields
        Field[] fields = Unsafe.class.getDeclaredFields();
        for (Field field : fields) {
            if (!field.getType().equals(Unsafe.class)) {
                continue;
            }

            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
                continue;
            }

            try {
                field.setAccessible(true);
                return (Unsafe) field.get(null);
            } catch (Exception ignored) {}
            break;
        }

        // Unavailable (e.g. Android hidden-API restrictions on Pojav/Mojo)!
        // Return null instead of throwing so class initialization succeeds;
        // callers must check isAvailable() and use a fallback path.
        return null;
    }

}
