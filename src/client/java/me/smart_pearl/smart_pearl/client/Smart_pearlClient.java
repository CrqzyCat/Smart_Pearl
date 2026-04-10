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

/**
 * Main client class for the Smart Pearl mod.
 *
 * <p>This mod provides:
 * <ul>
 *     <li>Fast ender pearl throwing via keybind</li>
 *     <li>Automatic hotbar refill from inventory</li>
 *     <li>HUD display for pearl count and cooldown</li>
 *     <li>Configurable refill timing</li>
 * </ul>
 *
 * <p>All logic is handled client-side using Fabric API events.
 */
public class Smart_pearlClient implements ClientModInitializer {

    /** Key binding used to trigger the smart pearl throw */
    private static KeyBinding pearlKey;

    /** Timestamp when the inventory screen was opened */
    private long invOpenTime = 0;

    /** Tracks whether the inventory was open in the previous tick */
    private boolean wasInvOpen = false;

    /**
     * Current step in the pearl execution sequence:
     * 0 = idle
     * 1 = switch slot
     * 2 = throw pearl
     * 3 = switch back
     */
    private int step = 0;

    /** Previously selected hotbar slot before throwing */
    private int oldSlot = -1;

    /** Hotbar slot containing the ender pearl */
    private int pearlSlot = -1;

    /** Runtime configuration instance */
    public static ConfigData config = new ConfigData();

    /** Path to the config file inside Fabric config directory */
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("smart_pearl.json").toFile();

    /** Gson instance for serialization/deserialization */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Configuration data class.
     *
     * <p>Stores user-defined values that are persisted to disk.
     */
    public static class ConfigData {
        /**
         * Time window (in seconds) after closing inventory
         * in which the mod will attempt to refill pearls.
         */
        public float refillWindow = 0.30f;
    }

    @Override
    public void onInitializeClient() {
        /** Load config from file (if present) */
        loadConfig();

        /** Register key binding for smart pearl throw */
        pearlKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Throw Smart Pearl",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));

        /**
         * Register client-side command:
         * /smartpearl → opens config GUI
         */
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("smartpearl").executes(context -> {
                MinecraftClient client = MinecraftClient.getInstance();

                // Ensure GUI opens on main thread
                client.execute(() -> client.setScreen(new ConfigScreen()));
                return 1;
            }));
        });

        /**
         * HUD rendering:
         * Displays total pearl count + cooldown indicator
         */
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden) return;

            ItemStack pearlStack = null;
            int totalPearls = 0;

            /** Count all pearls in inventory */
            for (int i = 0; i < client.player.getInventory().size(); i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isOf(Items.ENDER_PEARL)) {
                    if (pearlStack == null) pearlStack = stack;
                    totalPearls += stack.getCount();
                }
            }

            /** Render HUD only if player has pearls */
            if (totalPearls > 0 && pearlStack != null) {
                int x = context.getScaledWindowWidth() / 2 + 95;
                int y = context.getScaledWindowHeight() - 20;

                context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);

                String countText = "x" + totalPearls;
                float scale = 0.7f;

                context.getMatrices().pushMatrix();
                context.getMatrices().translate(x + 12, y + 11);
                context.getMatrices().scale(scale, scale);

                context.drawTextWithShadow(client.textRenderer, countText, 0, 0, 0xFFFFFFFF);

                context.getMatrices().popMatrix();

                /** Display cooldown if pearl is currently cooling down */
                float cooldown = client.player.getItemCooldownManager().getCooldownProgress(pearlStack, 0f);
                if (cooldown > 0) {
                    String cdText = String.format("%.1fs", cooldown * 1.5);
                    int cdWidth = client.textRenderer.getWidth(cdText);
                    context.drawTextWithShadow(client.textRenderer, "§c" + cdText, x + 8 - (cdWidth / 2), y - 12, 0xFFFFFFFF);
                }
            }
        });

        /**
         * Main client tick loop:
         * Handles key input, pearl sequence, and auto-refill logic
         */
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            /** Start pearl sequence on key press */
            while (pearlKey.wasPressed() && step == 0) startPearlSequence(client);

            /** Continue execution if sequence active */
            if (step > 0) runSequence(client);

            boolean isInv = client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen;

            /** Track inventory open/close timing */
            if (isInv && !wasInvOpen) invOpenTime = System.currentTimeMillis();
            else if (!isInv && wasInvOpen) {
                if (System.currentTimeMillis() - invOpenTime <= (config.refillWindow * 1000L)) {
                    tryRefill(client);
                }
            }
            wasInvOpen = isInv;
        });
    }

    /**
     * Initializes the pearl throwing sequence.
     *
     * <p>Searches hotbar for a usable pearl and prepares slot switching.
     */
    private void startPearlSequence(MinecraftClient client) {
        int found = -1;
        ItemStack pearlStack = null;

        /** Search hotbar (slots 0–8) */
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(Items.ENDER_PEARL)) {
                found = i;
                pearlStack = stack;
                break;
            }
        }

        /** Only proceed if pearl exists and is not on cooldown */
        if (found != -1 && pearlStack != null) {
            if (client.player.getItemCooldownManager().isCoolingDown(pearlStack)) return;

            this.oldSlot = client.player.getInventory().getSelectedSlot();
            this.pearlSlot = found;
            this.step = 1;
        }
    }

    /**
     * Executes the multi-step pearl throwing sequence.
     *
     * <p>Steps:
     * <ol>
     *     <li>Switch to pearl slot</li>
     *     <li>Use pearl</li>
     *     <li>Switch back</li>
     * </ol>
     */
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

    /**
     * Attempts to refill the hotbar with pearls from main inventory.
     *
     * <p>Triggered after closing inventory within configured time window.
     */
    private void tryRefill(MinecraftClient client) {
        int invPearl = -1;

        /** Search inventory (slots 9–35) */
        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) {
                invPearl = i;
                break;
            }
        }

        /** Move pearl into hotbar if possible */
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

    /** Saves config to disk */
    public static void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Loads config from disk if available */
    private void loadConfig() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                config = GSON.fromJson(reader, ConfigData.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Simple config GUI screen.
     *
     * <p>Allows user to adjust refill timing via slider.
     */
    public static class ConfigScreen extends Screen {

        public ConfigScreen() {
            super(Text.literal("Smart Pearl Settings"));
        }

        @Override
        protected void init() {
            int x = this.width / 2 - 100;

            /** Slider for refill timing */
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

            /** Save + close button */
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