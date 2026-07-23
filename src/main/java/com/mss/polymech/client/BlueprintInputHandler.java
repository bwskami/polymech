package com.mss.polymech.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mss.polymech.Polymech;
import com.mss.polymech.item.BlueprintToolItem;
import com.mss.polymech.network.MachinePlacementPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class BlueprintInputHandler {

    public static final KeyMapping BLUEPRINT_CANCEL_KEY = new KeyMapping(
            "key.poly_mech.blueprint_cancel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.misc"
    );

    public static final KeyMapping BLUEPRINT_CYCLE_MODE_KEY = new KeyMapping(
            "key.poly_mech.blueprint_cycle_mode",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.misc"
    );

    public static final KeyMapping BLUEPRINT_CYCLE_AXIS_KEY = new KeyMapping(
            "key.poly_mech.blueprint_cycle_axis",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            "key.categories.misc"
    );

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (BlueprintPreviewState.isActive()) {
            if (BLUEPRINT_CANCEL_KEY.consumeClick()) {
                BlueprintPreviewState.exit();
                BlueprintToolItem.setSelectedMachineId(null);
                return;
            }
            if (BLUEPRINT_CYCLE_MODE_KEY.consumeClick()) {
                BlueprintPreviewState.cycleMode();
            }
            if (BLUEPRINT_CYCLE_AXIS_KEY.consumeClick()) {
                if (BlueprintPreviewState.getMode() == BlueprintPreviewState.Mode.XYZ) {
                    BlueprintPreviewState.cycleAxis();
                }
            }
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        if (!(player.getMainHandItem().getItem() instanceof BlueprintToolItem)) return;
        if (!BlueprintPreviewState.isActive()) return;

        event.setCanceled(true);

        double delta = event.getScrollDeltaY();
        int scroll = delta > 0 ? 1 : -1;

        switch (BlueprintPreviewState.getMode()) {
            case FACING -> {
                Direction current = BlueprintPreviewState.getFacing();
                Direction[] horizontals = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
                int idx = 0;
                for (int i = 0; i < horizontals.length; i++) {
                    if (horizontals[i] == current) { idx = i; break; }
                }
                idx = (idx + scroll + 4) % 4;
                BlueprintPreviewState.setFacing(horizontals[idx]);
            }
            case XYZ -> BlueprintPreviewState.adjustOffset(scroll);
            case CONFIRM -> {}
        }
    }

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != 1 || event.getAction() != 1) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        if (!(player.getMainHandItem().getItem() instanceof BlueprintToolItem)) return;
        if (!BlueprintPreviewState.isActive()) return;

        event.setCanceled(true);

        if (BlueprintPreviewState.getMode() == BlueprintPreviewState.Mode.CONFIRM) {
            String machineId = BlueprintPreviewState.getMachineId();
            BlockPos targetPos = BlueprintPreviewState.getTargetPos();
            Direction facing = BlueprintPreviewState.getFacing();
            PacketDistributor.sendToServer(new MachinePlacementPacket(targetPos, facing.getName().toLowerCase(), machineId));
            BlueprintPreviewState.exit();
            BlueprintToolItem.setSelectedMachineId(null);
        }
    }
}
