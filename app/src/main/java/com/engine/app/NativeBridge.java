package com.engine.app;

public class NativeBridge {
    static { System.loadLibrary("c++_shared"); }
    public static native String getKey();
}
