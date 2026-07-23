package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.network.MachinePlacementPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

public class BlueprintToolItem extends Item {

    private static final String REACH_MODIFIER_ID = "poly_mech:blueprint_reach";
    private static final double REACH_BONUS = 6.0;

    private static String selectedMachineId = null;

    public static void setSelectedMachineId(String machineId) {
        selectedMachineId = machineId;
    }

    public static String getSelectedMachineId() {
        return selectedMachineId;
    }

    public BlueprintToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        return ItemAttributeModifiers.builder()
                .add(Attributes.BLOCK_INTERACTION_RANGE,
                        new AttributeModifier(
                                ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "blueprint_reach"),
                                REACH_BONUS,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();

        if (player == null) {
            return InteractionResult.PASS;
        }

        if (selectedMachineId == null) {
            if (level.isClientSide()) {
                openMultiblockSelectionMenu(player);
            }
            return InteractionResult.SUCCESS;
        }

        if (level.isClientSide()) {
            BlockPos clickedPos = context.getClickedPos();
            Direction clickFace = context.getClickedFace();

            BlockPos targetPos = clickedPos.relative(clickFace);
            Direction facing = clickFace.getOpposite();
            if (facing.getAxis().isVertical()) {
                facing = player.getDirection().getOpposite();
            }

            PacketDistributor.sendToServer(new MachinePlacementPacket(targetPos, facing.name(), selectedMachineId));
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.SUCCESS;
    }

    private void openMultiblockSelectionMenu(Player player) {
        Polymech.LOGGER.info("Opening multiblock selection menu for player: {}", player.getName().getString());
    }
}
