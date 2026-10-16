package io.mesalabs.rezoss.vision;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.*;

/** Uses the unmodified Samsung DEX for its actual parcelable implementations. */
final class Wire {
    static final String PREFIX = "com.samsung.android.aioskernelservice.";
    static Class<?> type(String name) throws Exception { return Class.forName(PREFIX + name); }
    static Object get(Object value, String method) throws Exception {
        return value.getClass().getMethod(method).invoke(value);
    }
    static Object read(Parcel parcel, String name) throws Exception {
        Parcelable.Creator<?> creator = (Parcelable.Creator<?>) type(name).getField("CREATOR").get(null);
        return parcel.readTypedObject(creator);
    }
    static Parcelable result(List<?> ids, String name, String text) throws Exception {
        Object prompt = type("PromptTensor").getConstructor(String.class, String.class).newInstance(name, text);
        return (Parcelable) type("TaskIOTensor").getConstructor(List.class, List.class, List.class, List.class)
            .newInstance(ids, Collections.singletonList(prompt), Collections.emptyList(), Collections.emptyList());
    }
}
