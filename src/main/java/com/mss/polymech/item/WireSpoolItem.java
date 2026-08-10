package com.mss.polymech.item;

import com.mss.polymech.ModDataComponents;
import com.mss.polymech.powergrid.GridNode;
import com.mss.polymech.powergrid.GridNodes;
import com.mss.polymech.powergrid.GridWireType;
import com.mss.polymech.powergrid.WorldPowerGrid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 线轴：拉线工具。
 * <p>
 * 交互模式（与Create-Electro-Energetics一致）：
 * <ol>
 *   <li>右键电网方块节点 → 记录起点（SELECTED_NODE数据组件，物品带附魔光效提示）</li>
 *   <li>再右键另一节点 → 服务端校验并建立电线连接；非创造模式消耗1个线轴并返还空线轴</li>
 *   <li>Shift+右键 → 取消当前选中的起点</li>
 * </ol>
 * useOn会在客户端与服务端各执行一次：客户端负责设置选中组件与交互反馈，
 * 服务端负责连接校验与电网写入（选中组件在两端同步设置，保证服务端持有起点）。
 * </p>
 */
public class WireSpoolItem extends Item {

    private final GridWireType wireType;

    public WireSpoolItem(Properties properties, GridWireType wireType) {
        super(properties);
        this.wireType = wireType;
    }

    /** 线轴对应的电线类型 */
    public GridWireType getWireType() {
        return wireType;
    }

    /** 已选中起点时显示附魔光效 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModDataComponents.SELECTED_NODE.get());
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack heldItem = context.getItemInHand();
        if (player == null)
            return InteractionResult.PASS;

        // Shift+右键：取消当前选中的起点
        if (player.isShiftKeyDown()) {
            if (heldItem.has(ModDataComponents.SELECTED_NODE.get())) {
                heldItem.remove(ModDataComponents.SELECTED_NODE.get());
                if (level.isClientSide())
                    player.displayClientMessage(Component.translatable("message.poly_mech.wire_spool.cancelled"), true);
            }
            return InteractionResult.SUCCESS;
        }

        GridNode hoveredNode = GridNodes.closestNode(level, context.getClickLocation(), GridNodes.NODE_HIT_THRESHOLD);
        if (hoveredNode == null)
            return InteractionResult.PASS;

        if (heldItem.has(ModDataComponents.SELECTED_NODE.get())) {
            // 已有起点：客户端直接放行，由服务端执行连接校验与电网写入
            if (level.isClientSide())
                return InteractionResult.SUCCESS;

            GridNode originalNode = heldItem.get(ModDataComponents.SELECTED_NODE.get());
            heldItem.remove(ModDataComponents.SELECTED_NODE.get());
            if (originalNode == null)
                return InteractionResult.SUCCESS;

            // 校验：自身连接
            if (hoveredNode.equals(originalNode)) {
                player.displayClientMessage(Component.translatable("message.poly_mech.wire_spool.same_node"), true);
                return InteractionResult.SUCCESS;
            }

            WorldPowerGrid grid = WorldPowerGrid.get((ServerLevel) level);

            // 校验：重复连接
            if (grid.isConnected(originalNode, hoveredNode)) {
                player.displayClientMessage(Component.translatable("message.poly_mech.wire_spool.already_connected"), true);
                return InteractionResult.SUCCESS;
            }

            // 校验：拉线长度
            double distance = GridNodes.distanceBetween(level, originalNode, hoveredNode);
            if (distance > wireType.getMaxLength()) {
                player.displayClientMessage(
                        Component.translatable("message.poly_mech.wire_spool.too_far", wireType.getMaxLength()), true);
                return InteractionResult.SUCCESS;
            }

            // 执行连接
            if (grid.connect(originalNode, hoveredNode, wireType)) {
                if (!player.isCreative()) {
                    heldItem.shrink(1);
                    player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.EMPTY_SPOOL.get()));
                }
                player.displayClientMessage(Component.translatable("message.poly_mech.wire_spool.connected"), true);
            }
            return InteractionResult.SUCCESS;
        }

        // 第一次点击：记录起点（双端执行，服务端同步持有组件）
        heldItem.set(ModDataComponents.SELECTED_NODE.get(), hoveredNode);
        if (level.isClientSide())
            player.displayClientMessage(
                    Component.translatable("message.poly_mech.wire_spool.selected", nodeLabel(level, hoveredNode)), true);
        return InteractionResult.SUCCESS;
    }

    private static String nodeLabel(Level level, GridNode node) {
        var pos = GridNodes.getNodePosition(level, node);
        return pos == null
                ? node.sourcePos().toShortString()
                : node.sourcePos().toShortString() + " (" + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z + ")";
    }
}
