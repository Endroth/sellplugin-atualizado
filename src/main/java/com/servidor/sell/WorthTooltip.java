package com.servidor.sell;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mostra o valor de venda no tooltip do item (só no cliente, via pacotes).
 * O item de verdade NUNCA é alterado: sem risco de duplicar ou sujar o lore.
 */
public class WorthTooltip extends PacketListenerAbstract {

    private final SellPlugin plugin;
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();
    private volatile String linePrice, lineStack, lineUnsellable;
    private volatile boolean showStack, showUnsellable, allowCreative;
    private final AtomicBoolean errorLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstSeen = new AtomicBoolean(false);

    public WorthTooltip(SellPlugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        loadSettings();
    }

    /** Chamado no onLoad do plugin. */
    static boolean load(SellPlugin plugin) {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
        PacketEvents.getAPI().getSettings().checkForUpdates(false);
        PacketEvents.getAPI().load();
        return true;
    }

    public void enable() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        PacketEvents.getAPI().init();
        plugin.getLogger().info("[Tooltip] PacketEvents iniciado. Teste em modo SOBREVIVÊNCIA (no criativo fica desligado).");
    }

    public void disable() {
        PacketEvents.getAPI().terminate();
    }

    public void loadSettings() {
        var c = plugin.getConfig();
        linePrice = c.getString("tooltip.line-price", "&7Vale: &a{price} &7cada");
        lineStack = c.getString("tooltip.line-stack", "&7Pilha (&f{amount}&7): &a{total}");
        lineUnsellable = c.getString("tooltip.line-unsellable", "&cNão pode ser vendido");
        showStack = c.getBoolean("tooltip.show-stack-line", true);
        showUnsellable = c.getBoolean("tooltip.show-unsellable", true);
        allowCreative = c.getBoolean("tooltip.allow-creative", false);
    }

    /** @return true se ficou ligado */
    public boolean toggle(Player p) {
        if (disabled.remove(p.getUniqueId())) return true;
        disabled.add(p.getUniqueId());
        return false;
    }

    // ---------------- pacotes ----------------

    @Override
    public void onPacketSend(PacketSendEvent e) {
        try {
            if (e.getPacketType() != PacketType.Play.Server.SET_SLOT
                    && e.getPacketType() != PacketType.Play.Server.WINDOW_ITEMS) return;

            Object user = e.getPlayer();
            if (!(user instanceof Player p)) return;
            // No criativo o cliente devolve o item com lore pro servidor, então não mexemos.
            if (p.getGameMode() == GameMode.CREATIVE && !allowCreative) return;
            if (!p.hasPermission("sell.worth") || disabled.contains(p.getUniqueId())) return;

            if (firstSeen.compareAndSet(false, true)) {
                plugin.getLogger().info("[Tooltip] Funcionando: primeiro pacote de item interceptado para " + p.getName());
            }
            if (e.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                WrapperPlayServerSetSlot w = new WrapperPlayServerSetSlot(e);
                w.setItem(decorate(w.getItem()));
                e.markForReEncode(true);
            } else {
                WrapperPlayServerWindowItems w = new WrapperPlayServerWindowItems(e);
                List<com.github.retrooper.packetevents.protocol.item.ItemStack> out = new ArrayList<>();
                for (var it : w.getItems()) out.add(decorate(it));
                w.setItems(out);
                e.markForReEncode(true);
            }
        } catch (Throwable t) {
            // o pacote original segue sem mexer; avisa só a primeira vez
            if (errorLogged.compareAndSet(false, true)) {
                plugin.getLogger().warning("[Tooltip] Erro ao mexer no pacote: " + t);
                t.printStackTrace();
            }
        }
    }

    private Component line(String template) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(template)
                .decoration(TextDecoration.ITALIC, false);
    }

    private com.github.retrooper.packetevents.protocol.item.ItemStack decorate(
            com.github.retrooper.packetevents.protocol.item.ItemStack peItem) {
        if (peItem == null || peItem.isEmpty()) return peItem;

        org.bukkit.inventory.ItemStack it = SpigotConversionUtil.toBukkitItemStack(peItem);
        if (it == null || it.getType().isAir()) return peItem;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return peItem;

        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();

        // não mexe no botão verde do /sell
        for (Component c : lore) {
            if (PlainTextComponentSerializer.plainText().serialize(c).contains("Clique para vender")) return peItem;
        }

        double unit = plugin.getUnitPrice(it);
        int amount = it.getAmount();

        if (unit <= 0) {
            if (!showUnsellable) return peItem;
            lore.add(line(lineUnsellable));
        } else {
            lore.add(line(linePrice.replace("{price}", plugin.formatMoney(unit))));
            if (showStack && amount > 1) {
                lore.add(line(lineStack
                        .replace("{amount}", String.valueOf(amount))
                        .replace("{total}", plugin.formatMoney(unit * amount))));
            }
        }

        meta.lore(lore);
        org.bukkit.inventory.ItemStack copy = it.clone();
        copy.setItemMeta(meta);
        return SpigotConversionUtil.fromBukkitItemStack(copy);
    }
}
