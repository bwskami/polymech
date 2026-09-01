package com.mss.polymech.client.gui.widget.planet;

/**
 * 30 光年内的真实恒星系目录。每个系统独立成场景。
 */
public final class StarSystemCatalog {
    private StarSystemCatalog() {}

    static final StarSystem[] SYSTEMS = build();

    static StarSystem[] build() {
        StarSystem[] arr = new StarSystem[24];
        arr[0] = new StarSystem("太阳系", "Sol", 0x5EED1234L + 0 * 0x1234567L,
                new StarSpec("G2V", 1.000f, 0.850f, 0.450f, 1.000f, 1.0000f, 1.000f),
                0.000f, 0.000f, 0.000f, new int[]{1,2,3});
        arr[1] = new StarSystem("南门二", "Alpha Centauri", 0x5EED1234L + 1 * 0x1234567L,
                new StarSpec("G2V+K1V+M5.5V", 1.000f, 0.850f, 0.450f, 1.220f, 1.5200f, 1.100f),
                -1.636f, -3.815f, -1.368f, new int[]{0,2,3});
        arr[2] = new StarSystem("巴纳德星", "Barnard's Star", 0x5EED1234L + 2 * 0x1234567L,
                new StarSpec("M4V", 1.000f, 0.550f, 0.200f, 0.196f, 0.0035f, 0.144f),
                -0.052f, 0.488f, -5.940f, new int[]{6,0,1});
        arr[3] = new StarSystem("沃尔夫359", "Wolf 359", 0x5EED1234L + 3 * 0x1234567L,
                new StarSpec("M6V", 1.000f, 0.350f, 0.100f, 0.160f, 0.0011f, 0.090f),
                -7.503f, 0.958f, 2.137f, new int[]{10,4,0});
        arr[4] = new StarSystem("拉兰德21185", "Lalande 21185", 0x5EED1234L + 4 * 0x1234567L,
                new StarSpec("M2V", 1.000f, 0.550f, 0.250f, 0.390f, 0.0250f, 0.460f),
                -6.518f, 4.884f, 1.649f, new int[]{3,10,0});
        arr[5] = new StarSystem("天狼星", "Sirius", 0x5EED1234L + 5 * 0x1234567L,
                new StarSpec("A1V+DA", 0.850f, 0.900f, 1.000f, 1.710f, 25.4000f, 2.060f),
                -1.614f, -2.471f, 8.078f, new int[]{11,15,8});
        arr[6] = new StarSystem("罗斯154", "Ross 154", 0x5EED1234L + 6 * 0x1234567L,
                new StarSpec("M3.5V", 1.000f, 0.450f, 0.150f, 0.240f, 0.0050f, 0.170f),
                1.810f, -2.017f, -9.314f, new int[]{2,17,1});
        arr[7] = new StarSystem("罗斯248", "Ross 248", 0x5EED1234L + 7 * 0x1234567L,
                new StarSpec("M5.5V", 1.000f, 0.400f, 0.120f, 0.160f, 0.0011f, 0.120f),
                7.381f, 7.155f, -0.646f, new int[]{12,16,0});
        arr[8] = new StarSystem("天苑四", "Epsilon Eridani", 0x5EED1234L + 8 * 0x1234567L,
                new StarSpec("K2V", 1.000f, 0.700f, 0.400f, 0.740f, 0.3400f, 0.820f),
                6.204f, -1.726f, 8.293f, new int[]{14,20,5});
        arr[9] = new StarSystem("拉卡伊9352", "Lacaille 9352", 0x5EED1234L + 9 * 0x1234567L,
                new StarSpec("M0.5V", 1.000f, 0.600f, 0.300f, 0.460f, 0.0360f, 0.500f),
                8.530f, -6.137f, -2.016f, new int[]{13,18,19});
        arr[10] = new StarSystem("罗斯128", "Ross 128", 0x5EED1234L + 10 * 0x1234567L,
                new StarSpec("M4V", 1.000f, 0.500f, 0.200f, 0.200f, 0.0036f, 0.170f),
                -10.983f, 0.154f, 0.595f, new int[]{3,4,1});
        arr[11] = new StarSystem("南河三", "Procyon", 0x5EED1234L + 11 * 0x1234567L,
                new StarSpec("F5IV-V+DA", 1.000f, 0.950f, 0.800f, 1.860f, 6.9000f, 1.500f),
                -4.762f, 1.033f, 10.306f, new int[]{15,5,3});
        arr[12] = new StarSystem("天津增廿九", "61 Cygni", 0x5EED1234L + 12 * 0x1234567L,
                new StarSpec("K5V+K7V", 1.000f, 0.650f, 0.350f, 0.670f, 0.1500f, 0.700f),
                6.612f, 7.128f, -5.953f, new int[]{7,2,21});
        arr[13] = new StarSystem("印第安座ε", "Epsilon Indi", 0x5EED1234L + 13 * 0x1234567L,
                new StarSpec("K5V", 1.000f, 0.650f, 0.350f, 0.730f, 0.2200f, 0.750f),
                5.640f, -9.874f, -3.152f, new int[]{19,9,1});
        arr[14] = new StarSystem("天仓五", "Tau Ceti", 0x5EED1234L + 14 * 0x1234567L,
                new StarSpec("G8V", 1.000f, 0.900f, 0.700f, 0.790f, 0.5200f, 0.780f),
                10.286f, -3.260f, 5.017f, new int[]{8,9,18});
        arr[15] = new StarSystem("鲁伊顿星", "Luyten's Star", 0x5EED1234L + 15 * 0x1234567L,
                new StarSpec("M3.5V", 1.000f, 0.450f, 0.150f, 0.350f, 0.0088f, 0.260f),
                -4.391f, 1.072f, 11.439f, new int[]{11,5,3});
        arr[16] = new StarSystem("蒂加登星", "Teegarden's Star", 0x5EED1234L + 16 * 0x1234567L,
                new StarSpec("M6.5V", 1.000f, 0.350f, 0.080f, 0.100f, 0.0007f, 0.089f),
                7.204f, 7.696f, 6.718f, new int[]{7,8,14});
        arr[17] = new StarSystem("沃尔夫1061", "Wolf 1061", 0x5EED1234L + 17 * 0x1234567L,
                new StarSpec("M3V", 1.000f, 0.500f, 0.200f, 0.260f, 0.0100f, 0.290f),
                -4.004f, -2.911f, -13.096f, new int[]{6,2,1});
        arr[18] = new StarSystem("格利泽876", "Gliese 876", 0x5EED1234L + 18 * 0x1234567L,
                new StarSpec("M4V", 1.000f, 0.500f, 0.200f, 0.360f, 0.0130f, 0.370f),
                14.104f, -3.677f, -4.312f, new int[]{9,14,19});
        arr[19] = new StarSystem("格利泽832", "Gliese 832", 0x5EED1234L + 19 * 0x1234567L,
                new StarSpec("M1.5V", 1.000f, 0.550f, 0.250f, 0.480f, 0.0280f, 0.450f),
                8.436f, -12.151f, -6.357f, new int[]{13,9,18});
        arr[20] = new StarSystem("波江座40", "40 Eridani", 0x5EED1234L + 20 * 0x1234567L,
                new StarSpec("K1V", 1.000f, 0.750f, 0.450f, 0.810f, 0.4600f, 0.840f),
                6.321f, -1.986f, 14.892f, new int[]{8,5,14});
        arr[21] = new StarSystem("牛郎星", "Altair", 0x5EED1234L + 21 * 0x1234567L,
                new StarSpec("A7V", 0.850f, 0.900f, 1.000f, 1.830f, 10.6000f, 1.790f),
                7.669f, 2.584f, -14.608f, new int[]{6,12,2});
        arr[22] = new StarSystem("北落师门", "Fomalhaut", 0x5EED1234L + 22 * 0x1234567L,
                new StarSpec("A3V", 0.800f, 0.900f, 1.000f, 1.840f, 16.6000f, 1.920f),
                21.020f, -12.398f, -5.869f, new int[]{18,19,9});
        arr[23] = new StarSystem("织女一", "Vega", 0x5EED1234L + 23 * 0x1234567L,
                new StarSpec("A0V", 0.750f, 0.850f, 1.000f, 2.360f, 40.1000f, 2.140f),
                3.115f, 15.665f, -19.233f, new int[]{21,12,6});
        return arr;
    }

    public static StarSystem[] systems() { return SYSTEMS; }
    public static int size() { return SYSTEMS.length; }
    public static StarSystem get(int i) { return SYSTEMS[i]; }
}