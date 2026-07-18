package com.mss.polymech.ldlib;

import com.lowdragmc.lowdraglib2.plugin.ILDLibPlugin;
import com.lowdragmc.lowdraglib2.plugin.LDLibPlugin;
import com.mss.polymech.Polymech;

@LDLibPlugin // 不需要modID参数
public class PolymechLDLibPlugin implements ILDLibPlugin {
    @Override
    public void onLoad() {
        // 在这里注册LDLib2相关资源
        Polymech.LOGGER.info("Polymech LDLib2 plugin loaded");
    }
}