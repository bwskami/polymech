package com.mss.polymech.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * 网络调试仪（AE 网络工具风格）。
 * <p>
 * 纯标记物品：拿在手上（主手或副手）时，客户端渲染器会高亮显示：
 * </p>
 * <ul>
 *   <li><b>传送带线路</b>（TransportLine）：每条线路一个颜色，线首额外标记，
 *       用于直观验证线路组网是否正确</li>
 *   <li><b>管道网络</b>：客户端按连接属性泛洪出的连通域，每个网络一个颜色</li>
 * </ul>
 *
 * @see com.mss.polymech.client.NetworkOverlayRenderer
 */
public class NetworkToolItem extends Item {

    public NetworkToolItem(Properties properties) {
        super(properties);
    }

    /** 玩家是否在主手或副手持有网络调试仪 */
    public static boolean isHolding(@Nullable Player player) {
        if (player == null) return false;
        return player.getMainHandItem().is(ModItems.NETWORK_TOOL.get())
                || player.getOffhandItem().is(ModItems.NETWORK_TOOL.get());
    }
}
