package me.smart_pearl.smart_pearl.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public class Smart_pearlClient implements ClientModInitializer {

    private static KeyBinding pearlKey;
    private long inventoryOpenTime = 0;
    private boolean wasInventoryOpen = false;

    @Override
    public void onInitializeClient() {
        pearlKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Throw Smart Pearl",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (pearlKey.wasPressed()) {
                executeSmartPearl(client);
            }

            // 0.3s Refill Logic
            boolean isInvOpen = client.currentScreen instanceof InventoryScreen;
            if (isInvOpen && !wasInventoryOpen) {
                inventoryOpenTime = System.currentTimeMillis();
            } else if (!isInvOpen && wasInventoryOpen) {
                long duration = System.currentTimeMillis() - inventoryOpenTime;
                if (duration <= 300) {
                    tryRefillHotbar(client);
                }
            }
            wasInventoryOpen = isInvOpen;
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            renderPearlHud(drawContext, MinecraftClient.getInstance());
        });
    }

    private void executeSmartPearl(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null || client.getNetworkHandler() == null) return;

        boolean isMoving = client.options.forwardKey.isPressed() ||
                client.options.backKey.isPressed() ||
                client.options.leftKey.isPressed() ||
                client.options.rightKey.isPressed();

        int pearlSlot = -1;
        ItemStack pearlStack = null;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                if (!isMoving || i < 9) {
                    pearlSlot = i;
                    pearlStack = stack;
                    break;
                }
            }
        }

        if (pearlSlot != -1 && pearlStack != null) {
            // FIX for image_9a47be.png: Use the ItemStack for the cooldown check
            if (client.player.getItemCooldownManager().isCoolingDown(pearlStack)) return;

            int oldSlot = client.player.getInventory().getSelectedSlot();

            if (pearlSlot < 9) {
                if (pearlSlot != oldSlot) {
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(pearlSlot));
                }
                sendInteractPacket(client);
                if (pearlSlot != oldSlot) {
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));
                }
            } else {
                // Stop sprinting to avoid illegal packet kicks
                client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
                client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, pearlSlot, oldSlot, SlotActionType.SWAP, client.player);
                sendInteractPacket(client);
                client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, pearlSlot, oldSlot, SlotActionType.SWAP, client.player);
            }
        }
    }

    private void tryRefillHotbar(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null || client.getNetworkHandler() == null) return;

        int pearlInInv = -1;
        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) {
                pearlInInv = i;
                break;
            }
        }

        if (pearlInInv != -1) {
            for (int h = 0; h < 9; h++) {
                ItemStack stack = client.player.getInventory().getStack(h);
                if (stack.isEmpty() || (stack.isOf(Items.ENDER_PEARL) && stack.getCount() < 16)) {
                    // Prevents kicks during automatic refill while running
                    if (client.player.isSprinting()) {
                        client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
                    }
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, pearlInInv, h, SlotActionType.SWAP, client.player);
                    break;
                }
            }
        }
    }

    private void renderPearlHud(DrawContext context, MinecraftClient client) {
        if (client.player == null || client.options.hudHidden) return;

        int total = 0;
        int hotbar = 0;
        ItemStack displayStack = null;

        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (s.isOf(Items.ENDER_PEARL)) {
                total += s.getCount();
                if (i < 9) hotbar += s.getCount();
                if (displayStack == null) displayStack = s;
            }
        }

        if (total <= 0 || displayStack == null) return;

        int x = (context.getScaledWindowWidth() / 2) + 95;
        int y = context.getScaledWindowHeight() - 20;

        context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);
        int color = (hotbar > 0) ? 0xFFFFFFFF : 0xFFFF5555;
        context.drawTextWithShadow(client.textRenderer, "x" + total, x + 18, y + 6, color);

        // FIX for image_9a47be.png: Use ItemStack for cooldown progress
        float progress = client.player.getItemCooldownManager().getCooldownProgress(displayStack, 0.0f);
        if (progress > 0.0f) {
            String cooldownText = String.format("%.1fs", progress * 2.5f);
            context.drawTextWithShadow(client.textRenderer, cooldownText, x, y - 10, 0xFFFF5555);
        }
    }

    private void sendInteractPacket(MinecraftClient client) {
        if (client.getNetworkHandler() != null && client.player != null) {
            client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch()));
        }
    }
}