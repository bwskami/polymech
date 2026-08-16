package com.mss.polymech.powergrid;

import org.jetbrains.annotations.Nullable;

/**
 * 钳形表最近一次测量结果（服务端包写入，客户端 HUD 读取）。
 */
public final class ClampMeterMeasurementState {

    private static volatile GridConnection connection;
    private static volatile double current;
    private static volatile int voltage;

    private ClampMeterMeasurementState() {}

    public static void set(@Nullable GridConnection connection, double current, int voltage) {
        ClampMeterMeasurementState.connection = connection;
        ClampMeterMeasurementState.current = current;
        ClampMeterMeasurementState.voltage = voltage;
    }

    @Nullable
    public static GridConnection connection() {
        return connection;
    }

    public static double current() {
        return current;
    }

    public static int voltage() {
        return voltage;
    }

    public static void clear() {
        connection = null;
        current = 0;
        voltage = 0;
    }
}
