package me.smart_pearl.smart_pearl.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public class Smart_pearlClient implements ClientModInitializer {

    private static KeyBinding pearlKey;

    @Override
    public void onInitializeClient() {
        pearlKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smart_pearl.throw",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.interactionManager == null) return;
            while (pearlKey.wasPressed()) {
                executeSmartPearl(client);
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden) return;
            renderPearlHud(drawContext, client);
        });
    }

    private void renderPearlHud(DrawContext context, MinecraftClient client) {
        TextRenderer renderer = client.textRenderer;
        int totalPearls = 0;
        ItemStack referenceStack = ItemStack.EMPTY;

        // Inventar-Check
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                totalPearls += stack.getCount();
                if (referenceStack.isEmpty()) referenceStack = stack;
            }
        }

        // Position: 95 Pixel rechts von der Hotbar-Mitte
        int x = (context.getScaledWindowWidth() / 2) + 95;
        int y = context.getScaledWindowHeight() - 22;

        // 1. ZUERST DEN TEXT ZEICHNEN (damit er nicht vom Icon verdeckt wird)
        // Die Zahl "xAnzahl"
        String countText = "x" + totalPearls;
        context.drawText(renderer, countText, x + 20, y + 5, 0xFFFFFF, true);

        // 2. DEN COOLDOWN ZEICHNEN
        if (!referenceStack.isEmpty()) {
            float progress = client.player.getItemCooldownManager().getCooldownProgress(referenceStack, 0.0f);
            if (progress > 0.0f) {
                // Zeige den Fortschritt als Text über dem Icon
                String cdText = String.format("%.1fs", progress * 1.0f);
                context.drawText(renderer, cdText, x, y - 12, 0xFF5555, true);
            }
        }

        // 3. DANACH DAS ICON (DrawItem setzt oft den Z-Index zurück)
        context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);
    }

    private void executeSmartPearl(MinecraftClient client) {
        int pearlSlotIndex = -1;
        ItemStack pearlStack = ItemStack.EMPTY;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                pearlSlotIndex = i;
                pearlStack = stack;
                break;
            }
        }

        if (pearlSlotIndex != -1 && !pearlStack.isEmpty()) {
            if (client.player.getItemCooldownManager().isCoolingDown(pearlStack)) return;

            int originalSlot = client.player.getInventory().getSelectedSlot();
            float yaw = client.player.getYaw();
            float pitch = client.player.getPitch();

            if (pearlSlotIndex < 9) {
                client.player.getInventory().setSelectedSlot(pearlSlotIndex);
                client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, yaw, pitch));
                client.player.getInventory().setSelectedSlot(originalSlot);
            } else {
                int syncId = client.player.currentScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, pearlSlotIndex, originalSlot, SlotActionType.SWAP, client.player);
                client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, yaw, pitch));
                client.interactionManager.clickSlot(syncId, pearlSlotIndex, originalSlot, SlotActionType.SWAP, client.player);
            }
        }
    }
}