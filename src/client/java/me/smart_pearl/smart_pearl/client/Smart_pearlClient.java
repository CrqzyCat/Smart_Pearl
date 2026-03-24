package me.smart_pearl.smart_pearl.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Smart_pearlClient implements ClientModInitializer {

    private static KeyBinding pearlKey;
    private long invOpenTime = 0;
    private boolean wasInvOpen = false;

    private int step = 0;
    private int oldSlot = -1;
    private int pearlSlot = -1;

    public static ConfigData config = new ConfigData();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("smart_pearl.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class ConfigData {
        public float refillWindow = 0.30f;
    }

    @Override
    public void onInitializeClient() {
        loadConfig();

        pearlKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smart_pearl.throw",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("smartpearl").executes(context -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> client.setScreen(new ConfigScreen()));
                return 1;
            }));
        });

        // HUD
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden) return;

            ItemStack pearlStack = null;
            int totalPearls = 0;

            for (int i = 0; i < client.player.getInventory().size(); i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isOf(Items.ENDER_PEARL)) {
                    if (pearlStack == null) pearlStack = stack;
                    totalPearls += stack.getCount();
                }
            }

            if (totalPearls > 0 && pearlStack != null) {
                int x = context.getScaledWindowWidth() / 2 + 95;
                int y = context.getScaledWindowHeight() - 20;

                // Item
                context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);

                // Count (skaliert)
                String countText = "x" + totalPearls;
                float scale = 0.7f;

                context.getMatrices().pushMatrix();
                context.getMatrices().translate(x + 18, y + 11);
                context.getMatrices().scale(scale, scale);

                context.drawTextWithShadow(client.textRenderer, countText, 0, 0, 0xFFFFFFFF);

                context.getMatrices().popMatrix();

                // Cooldown
                float cooldown = client.player.getItemCooldownManager().getCooldownProgress(pearlStack, 0f);
                if (cooldown > 0) {
                    String cdText = String.format("%.1fs", cooldown * 1.5);
                    int cdWidth = client.textRenderer.getWidth(cdText);
                    context.drawTextWithShadow(client.textRenderer, "§c" + cdText, x + 8 - (cdWidth / 2), y - 12, 0xFFFFFFFF);
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (pearlKey.wasPressed() && step == 0) startPearlSequence(client);
            if (step > 0) runSequence(client);

            boolean isInv = client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen;

            if (isInv && !wasInvOpen) invOpenTime = System.currentTimeMillis();
            else if (!isInv && wasInvOpen) {
                if (System.currentTimeMillis() - invOpenTime <= (config.refillWindow * 1000L)) {
                    tryRefill(client);
                }
            }
            wasInvOpen = isInv;
        });
    }

    private void startPearlSequence(MinecraftClient client) {
        int found = -1;
        ItemStack pearlStack = null;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(Items.ENDER_PEARL)) {
                found = i;
                pearlStack = stack;
                break;
            }
        }

        if (found != -1 && pearlStack != null) {
            if (client.player.getItemCooldownManager().isCoolingDown(pearlStack)) return;

            this.oldSlot = client.player.getInventory().getSelectedSlot();
            this.pearlSlot = found;
            this.step = 1;
        }
    }

    private void runSequence(MinecraftClient client) {
        switch (step) {
            case 1 -> {
                client.player.getInventory().setSelectedSlot(pearlSlot);
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(pearlSlot));
                step = 2;
            }
            case 2 -> {
                client.getNetworkHandler().sendPacket(
                        new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch())
                );
                step = 3;
            }
            case 3 -> {
                client.player.getInventory().setSelectedSlot(oldSlot);
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));
                step = 0;
            }
        }
    }

    private void tryRefill(MinecraftClient client) {
        int invPearl = -1;

        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) {
                invPearl = i;
                break;
            }
        }

        if (invPearl != -1) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isEmpty() || (stack.isOf(Items.ENDER_PEARL) && stack.getCount() < 16)) {
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            invPearl,
                            i,
                            SlotActionType.SWAP,
                            client.player
                    );
                    break;
                }
            }
        }
    }

    public static void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                config = GSON.fromJson(reader, ConfigData.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static class ConfigScreen extends Screen {

        public ConfigScreen() {
            super(Text.literal("Smart Pearl Settings"));
        }

        @Override
        protected void init() {
            int x = this.width / 2 - 100;

            this.addDrawableChild(new SliderWidget(
                    x, 60, 200, 20,
                    Text.literal(String.format("Refill Timer: %.2fs", config.refillWindow)),
                    (config.refillWindow - 0.01) / 0.99
            ) {
                @Override
                protected void updateMessage() {
                    this.setMessage(Text.literal(String.format("Refill Timer: %.2fs", config.refillWindow)));
                }

                @Override
                protected void applyValue() {
                    config.refillWindow = 0.01f + (float) this.value * 0.99f;
                }
            });

            this.addDrawableChild(
                    ButtonWidget.builder(Text.literal("Done"), b -> {
                        saveConfig();
                        this.client.setScreen(null);
                    }).dimensions(x, 100, 200, 20).build()
            );
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            this.renderInGameBackground(context);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 25, 0xFFFFFFFF);
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}