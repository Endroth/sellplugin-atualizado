package com.servidor.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;

public class SellPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int BUTTON = 53;

    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private final Set<Material> blockedExact = EnumSet.noneOf(Material.class);
    private final List<String> blockedSuffix = new ArrayList<>();
    private double defaultPrice, bow, bowFlame, crossbow, crossbowQuick;
    private boolean sellOnClose;
    private String fallbackCommand;
    private Economy economy;
    private final TreeMap<Integer, Double> enchantPct = new TreeMap<>();
    private final List<Map.Entry<String, Double>> pricePatterns = new ArrayList<>();
    private boolean enchantEnabled;
    private boolean shulkerContents;
    private double shulkerBoxPrice = 1000;
    private double enchantCap;
    private WorthTooltip tooltip;
    private boolean tooltipLoaded;
    private boolean soundEnabled;
    private String soundKey;
    private float soundVolume, soundPitch;
    private File customFile;
    private YamlConfiguration custom;
    private double machadoPrice;
    private final Map<String, Double> bookPrices = new java.util.concurrent.ConcurrentHashMap<>();
    private final NamespacedKey machadoKey = NamespacedKey.fromString("sellaxe:machado_vendedor");
    private record BookRef(String key, int level) { }
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("pt", "BR"));

    /** Marca o inventário como sendo o menu de venda. */
    private static class SellHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public void onLoad() {
        // PacketEvents (já vem dentro do .jar) precisa carregar aqui
        try {
            tooltipLoaded = WorthTooltip.load(this);
        } catch (Throwable t) {
            tooltipLoaded = false;
            getLogger().warning("Tooltip de valor desativado: " + t);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(0);
        loadSettings();
        setupVault();
        getCommand("sell").setExecutor(this);
        getCommand("sell").setTabCompleter(this);
        getCommand("valoritem").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (tooltipLoaded) {
            try {
                tooltip = new WorthTooltip(this);
                tooltip.enable();
            } catch (Throwable t) {
                tooltip = null;
                getLogger().warning("Tooltip de valor desativado: " + t);
            }
        }
        getLogger().info("SellPlugin ligado. Economia: " + (economy != null ? "Vault" : "comando fallback"));
    }

    @Override
    public void onDisable() {
        if (tooltip != null) {
            try { tooltip.disable(); } catch (Throwable ignored) { }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellHolder) p.closeInventory();
        }
    }

    private void setupVault() {
        economy = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        prices.clear();
        blockedExact.clear();
        blockedSuffix.clear();

        defaultPrice = c.getDouble("default-price", 1);
        sellOnClose = c.getBoolean("sell-on-close", true);
        fallbackCommand = c.getString("fallback-command", "eco give {player} {amount}");
        soundEnabled = c.getBoolean("sound.enabled", true);
        soundKey = c.getString("sound.name", "entity.experience_orb.pickup");
        soundVolume = (float) c.getDouble("sound.volume", 1.0);
        soundPitch = (float) c.getDouble("sound.pitch", 1.2);
        if (tooltip != null) tooltip.loadSettings();
        bow = c.getDouble("special.bow", 100);
        bowFlame = c.getDouble("special.bow-flame", 12000);
        crossbow = c.getDouble("special.crossbow", 100);
        crossbowQuick = c.getDouble("special.crossbow-quick-charge", 10000);
        machadoPrice = c.getDouble("special.machado-vendedor", 50000);
        bookPrices.clear();
        loadBookPrices(c.getConfigurationSection("book-prices"));

        for (String s : c.getStringList("blocked")) {
            if (s.startsWith("*")) {
                blockedSuffix.add(s.substring(1).toUpperCase());
            } else {
                Material m = Material.matchMaterial(s);
                if (m != null) blockedExact.add(m);
            }
        }
        enchantPct.clear();
        pricePatterns.clear();
        enchantEnabled = c.getBoolean("enchant-bonus.enabled", true);
        shulkerContents = c.getBoolean("shulker-includes-contents", true);
        shulkerBoxPrice = c.getDouble("shulker-box-price", 1000);
        enchantCap = c.getDouble("enchant-bonus.cap-percent", 400);
        var ep = c.getConfigurationSection("enchant-bonus.percent-by-level");
        if (ep != null) {
            for (String k : ep.getKeys(false)) {
                try { enchantPct.put(Integer.parseInt(k), ep.getDouble(k)); }
                catch (NumberFormatException ignored) { }
            }
        }
        if (enchantPct.isEmpty()) {
            enchantPct.put(1, 20.0); enchantPct.put(2, 40.0); enchantPct.put(3, 60.0);
            enchantPct.put(4, 80.0); enchantPct.put(5, 95.0);
        }
        var pp = c.getConfigurationSection("price-patterns");
        if (pp != null) {
            for (String k : pp.getKeys(false))
                pricePatterns.add(Map.entry(k.toUpperCase(), pp.getDouble(k)));
        }

        var sec = c.getConfigurationSection("prices");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) { getLogger().warning("Item inválido em prices: " + key); continue; }
                prices.put(m, sec.getDouble(key));
            }
        }

        // preços editados dentro do jogo (sobrescrevem o config.yml)
        customFile = new File(getDataFolder(), "precos-editados.yml");
        custom = YamlConfiguration.loadConfiguration(customFile);
        var cp = custom.getConfigurationSection("prices");
        if (cp != null) {
            for (String key : cp.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) prices.put(m, cp.getDouble(key));
            }
        }
        if (custom.contains("settings.sell-on-close")) sellOnClose = custom.getBoolean("settings.sell-on-close");
        if (custom.contains("special.machado-vendedor")) machadoPrice = custom.getDouble("special.machado-vendedor");
        loadBookPrices(custom.getConfigurationSection("books"));
        if (custom.contains("special.bow")) bow = custom.getDouble("special.bow");
        if (custom.contains("special.bow-flame")) bowFlame = custom.getDouble("special.bow-flame");
        if (custom.contains("special.crossbow")) crossbow = custom.getDouble("special.crossbow");
        if (custom.contains("special.crossbow-quick-charge")) crossbowQuick = custom.getDouble("special.crossbow-quick-charge");
    }

    private void saveCustom() {
        try { custom.save(customFile); }
        catch (IOException ex) { getLogger().severe("Não consegui salvar precos-editados.yml: " + ex.getMessage()); }
    }

    private static final List<String> SPECIALS = List.of("bow", "bow-flame", "crossbow", "crossbow-quick-charge", "machado-vendedor");

    private void loadBookPrices(org.bukkit.configuration.ConfigurationSection sec) {
        if (sec == null) return;
        for (String ench : sec.getKeys(false)) {
            var lv = sec.getConfigurationSection(ench);
            if (lv == null) continue;
            for (String level : lv.getKeys(false)) {
                bookPrices.put(ench.toLowerCase(Locale.ROOT) + ":" + level, lv.getDouble(level));
            }
        }
    }

    private boolean isMachado(ItemStack it) {
        return machadoKey != null && it != null && it.getType() == Material.NETHERITE_AXE && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(machadoKey, PersistentDataType.BYTE);
    }

    /** Preço específico de livro encantado (por encantamento + nível), ou null se não tiver. */
    private Double bookSpecific(ItemStack it) {
        if (bookPrices.isEmpty()) return null;
        if (!(it.getItemMeta() instanceof EnchantmentStorageMeta esm)) return null;
        Map<Enchantment, Integer> st = esm.getStoredEnchants();
        if (st.isEmpty()) return null;
        double sum = 0;
        for (Map.Entry<Enchantment, Integer> e : st.entrySet()) {
            Double v = bookPrices.get(e.getKey().getKey().getKey() + ":" + e.getValue());
            if (v == null) return null;
            sum += v;
        }
        return sum;
    }

    private ItemStack makeBook(BookRef ref) {
        Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(ref.key()));
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        if (en != null && book.getItemMeta() instanceof EnchantmentStorageMeta esm) {
            esm.addStoredEnchant(en, ref.level(), true);
            book.setItemMeta(esm);
        }
        return book;
    }

    private String bookName(BookRef ref) {
        return "Livro " + ref.key() + " " + ref.level();
    }

    private Double specialPrice(String key) {
        return switch (key) {
            case "bow" -> bow;
            case "bow-flame" -> bowFlame;
            case "crossbow" -> crossbow;
            case "crossbow-quick-charge" -> crossbowQuick;
            case "machado-vendedor" -> machadoPrice;
            default -> null;
        };
    }

    private void setSpecial(String key, double v) {
        switch (key) {
            case "bow" -> bow = v;
            case "bow-flame" -> bowFlame = v;
            case "crossbow" -> crossbow = v;
            case "crossbow-quick-charge" -> crossbowQuick = v;
            case "machado-vendedor" -> machadoPrice = v;
        }
        custom.set("special." + key, v);
        saveCustom();
    }

    /** Resolve "mao"/"hand", nome de item ou chave especial. Retorna null se não achar. */
    private Object resolveTarget(Player p, String arg) {
        String a = arg.toLowerCase(Locale.ROOT);
        if (SPECIALS.contains(a)) return a;
        if (a.equals("hand") || a.equals("mao") || a.equals("mão")) {
            if (p == null) return null;
            ItemStack it = p.getInventory().getItemInMainHand();
            if (it.getType().isAir()) return null;
            if (isMachado(it)) return "machado-vendedor";
            if (it.getType() == Material.ENCHANTED_BOOK && it.getItemMeta() instanceof EnchantmentStorageMeta esm
                    && !esm.getStoredEnchants().isEmpty()) {
                if (esm.getStoredEnchants().size() > 1) return new BookRef(null, 0);
                var en = esm.getStoredEnchants().entrySet().iterator().next();
                return new BookRef(en.getKey().getKey().getKey(), en.getValue());
            }
            if (it.getType() == Material.BOW)
                return it.containsEnchantment(Enchantment.FLAME) ? "bow-flame" : "bow";
            if (it.getType() == Material.CROSSBOW)
                return it.containsEnchantment(Enchantment.QUICK_CHARGE) ? "crossbow-quick-charge" : "crossbow";
            return it.getType();
        }
        Material m = Material.matchMaterial(arg);
        if (m == null || m.isAir() || !m.isItem()) return null;
        return m;
    }

    private boolean isBlocked(Material m) {
        if (blockedExact.contains(m)) return true;
        String n = m.name();
        for (String suf : blockedSuffix) if (n.endsWith(suf)) return true;
        return false;
    }

    /** Preço base (sem encantamentos): exato > padrão (*X / X*) > default-price. */
    private double basePrice(Material m) {
        return basePrice(m, defaultPrice);
    }

    private double basePrice(Material m, double fallback) {
        Double v = prices.get(m);
        if (v != null) return v;
        String n = m.name();
        for (Map.Entry<String, Double> e : pricePatterns) {
            String k = e.getKey();
            if (k.startsWith("*") && n.endsWith(k.substring(1))) return e.getValue();
            if (k.endsWith("*") && n.startsWith(k.substring(0, k.length() - 1))) return e.getValue();
        }
        return fallback;
    }

    private double pctForLevel(int level) {
        if (level <= 0) return 0;
        Double v = enchantPct.get(level);
        if (v != null) return v;
        return enchantPct.lastEntry().getValue(); // nível acima do último definido
    }

    /** Soma o bônus (%) de todos os encantamentos, ignorando o que já está no preço especial. */
    private double enchantBonus(ItemStack it, Enchantment skip) {
        if (!enchantEnabled) return 0;
        Map<Enchantment, Integer> all = new HashMap<>(it.getEnchantments());
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta esm) all.putAll(esm.getStoredEnchants());
        double total = 0;
        for (Map.Entry<Enchantment, Integer> e : all.entrySet()) {
            if (e.getKey().equals(skip)) continue;
            total += pctForLevel(e.getValue());
        }
        return Math.min(total, enchantCap);
    }

    /** Preço de UMA unidade do item (0 = não vende). */
    private double unitPrice(ItemStack it) {
        if (it == null) return 0;
        Material m = it.getType();
        if (m.isAir() || !m.isItem()) return 0;
        if (isMachado(it)) return machadoPrice; // preço fixo do MachadoVendedor
        boolean shulker = m.name().endsWith("SHULKER_BOX"); // shulker box NUNCA é bloqueada
        if (!shulker && isBlocked(m)) return 0;
        if (m == Material.ENCHANTED_BOOK) {
            Double bp = bookSpecific(it); // preço específico desse livro (encantamento + nível)
            if (bp != null) return bp;
        }

        double base;
        Enchantment skip = null;
        if (m == Material.BOW) {
            boolean flame = it.containsEnchantment(Enchantment.FLAME);
            base = flame ? bowFlame : bow;
            if (flame) skip = Enchantment.FLAME;
        } else if (m == Material.CROSSBOW) {
            boolean quick = it.containsEnchantment(Enchantment.QUICK_CHARGE);
            base = quick ? crossbowQuick : crossbow;
            if (quick) skip = Enchantment.QUICK_CHARGE;
        } else if (shulker) {
            base = basePrice(m, shulkerBoxPrice); // sem preço na config = usa shulker-box-price
        } else {
            base = basePrice(m);
        }
        if (base <= 0) return 0;
        double total = base * (1 + enchantBonus(it, skip) / 100.0);

        // shulker box: soma também o valor do que está dentro dela
        if (shulkerContents && m.name().endsWith("SHULKER_BOX")
                && it.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack c : box.getInventory().getContents()) {
                if (c != null && !c.getType().isAir()) total += unitPrice(c) * c.getAmount();
            }
        }
        return total;
    }

    /** API para outros plugins (ex.: ItemWorth): preço de UMA unidade (0 = não vende). */
    public double getUnitPrice(ItemStack it) {
        return unitPrice(it);
    }

    private double totalValue(Inventory inv) {
        double total = 0;
        for (int i = 0; i < BUTTON; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null) total += unitPrice(it) * it.getAmount();
        }
        return total;
    }

    private String money(double v) { return "$" + fmt.format(v); }

    private ItemStack buildButton(double total) {
        ItemStack b = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = b.getItemMeta();
        meta.displayName(Component.text("Vender itens", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Você vai ganhar: ", NamedTextColor.GRAY)
                        .append(Component.text(money(total), NamedTextColor.GREEN))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Clique para vender", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        b.setItemMeta(meta);
        return b;
    }

    private void open(Player p) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Sell"));
        holder.inv = inv;
        inv.setItem(BUTTON, buildButton(0));
        p.openInventory(inv);
    }

    private void refreshButton(Inventory inv) {
        inv.setItem(BUTTON, buildButton(totalValue(inv)));
    }

    /** Vende tudo que for vendável; devolve o resto. */
    private void sell(Player p, Inventory inv) {
        double total = 0;
        long count = 0;
        List<ItemStack> giveBack = new ArrayList<>();

        for (int i = 0; i < BUTTON; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            double unit = unitPrice(it);
            if (unit <= 0) {
                giveBack.add(it);
            } else {
                total += unit * it.getAmount();
                count += it.getAmount();
            }
            inv.setItem(i, null);
        }

        for (ItemStack it : giveBack) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }

        if (!giveBack.isEmpty()) {
            p.sendMessage(Component.text("Alguns itens não podem ser vendidos e foram devolvidos.", NamedTextColor.RED));
        }

        if (total > 0) {
            deposit(p, total);
            if (soundEnabled) {
                Location loc = p.getLocation();
                p.playSound(loc, soundKey, soundVolume, soundPitch);
            }
            p.sendMessage(Component.text("Você vendeu " + fmt.format(count) + " itens por ", NamedTextColor.GREEN)
                    .append(Component.text(money(total), NamedTextColor.DARK_GREEN)
                            .decoration(TextDecoration.BOLD, true)));
        }
    }

    private void deposit(Player p, double amount) {
        if (economy != null) {
            economy.depositPlayer(p, amount);
        } else {
            String cmd = fallbackCommand
                    .replace("{player}", p.getName())
                    .replace("{amount}", String.valueOf(Math.round(amount * 100.0) / 100.0));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    // ---------------- comando ----------------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("valoritem")) {
            if (!(s instanceof Player pl)) return true;
            if (tooltip == null) {
                pl.sendMessage(Component.text("O tooltip de valor não está ativo no servidor (veja o console).", NamedTextColor.RED));
                return true;
            }
            boolean on = tooltip.toggle(pl);
            pl.sendMessage(Component.text("Valor dos itens: " + (on ? "ligado." : "desligado."),
                    on ? NamedTextColor.GREEN : NamedTextColor.RED));
            pl.updateInventory();
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("sell.admin")) {
                s.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            setupVault();
            s.sendMessage(Component.text("Configuração recarregada.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("venderesc") || args[0].equalsIgnoreCase("esc"))) {
            if (!s.hasPermission("sell.admin")) {
                s.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
                return true;
            }
            if (args.length < 2) {
                s.sendMessage(Component.text("Vender ao fechar com ESC está "
                        + (sellOnClose ? "ligado" : "desligado") + ". Use: /sell venderesc on|off", NamedTextColor.YELLOW));
                return true;
            }
            String v = args[1].toLowerCase(Locale.ROOT);
            boolean on;
            if (v.equals("on") || v.equals("ligar") || v.equals("true")) on = true;
            else if (v.equals("off") || v.equals("desligar") || v.equals("false")) on = false;
            else {
                s.sendMessage(Component.text("Use: /sell venderesc on|off", NamedTextColor.YELLOW));
                return true;
            }
            sellOnClose = on;
            custom.set("settings.sell-on-close", on);
            saveCustom();
            s.sendMessage(Component.text("Vender ao fechar com ESC: " + (on ? "ligado." : "desligado."),
                    on ? NamedTextColor.GREEN : NamedTextColor.RED));
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("setprice") || args[0].equalsIgnoreCase("price")
                || args[0].equalsIgnoreCase("preco") || args[0].equalsIgnoreCase("setpreco"))) {
            return handlePrice(s, args);
        }
        if (!(s instanceof Player p)) {
            s.sendMessage("Só jogadores podem usar esse comando.");
            return true;
        }
        if (!p.hasPermission("sell.use")) {
            p.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return true;
        }
        open(p);
        return true;
    }

    private boolean handlePrice(CommandSender s, String[] args) {
        boolean set = args[0].equalsIgnoreCase("setprice") || args[0].equalsIgnoreCase("setpreco");
        Player p = s instanceof Player pl ? pl : null;

        if (set && !s.hasPermission("sell.admin")) {
            s.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("book")) {
            return handleBook(s, args, set);
        }
        if (args.length < 2 || (set && args.length < 3)) {
            s.sendMessage(Component.text(set
                    ? "Use: /sell setprice <item|hand> <preço>"
                    : "Use: /sell price <item|hand>", NamedTextColor.YELLOW));
            if (set) s.sendMessage(Component.text("Especiais: bow, bow-flame, crossbow, crossbow-quick-charge, machado-vendedor | Livros: /sell setprice book <encantamento> <nível> <preço>", NamedTextColor.GRAY));
            return true;
        }

        Object target = resolveTarget(p, args[1]);
        if (target == null) {
            s.sendMessage(Component.text("Item não encontrado (ou sem item na mão): " + args[1], NamedTextColor.RED));
            return true;
        }

        if (target instanceof BookRef br && br.key() == null) {
            s.sendMessage(Component.text("Esse livro tem mais de um encantamento. Use: /sell setprice book <encantamento> <nível> <preço>", NamedTextColor.RED));
            return true;
        }

        if (!set && target instanceof BookRef br) {
            s.sendMessage(Component.text(bookName(br) + " vende por " + money(unitPrice(makeBook(br))) + " cada.", NamedTextColor.GREEN));
            return true;
        }

        if (!set) {
            double v = target instanceof String key ? specialPrice(key) : unitPrice(new ItemStack((Material) target));
            String nome = target instanceof String key ? key : ((Material) target).name();
            s.sendMessage(Component.text(nome + " vende por " + money(v) + " cada.", NamedTextColor.GREEN));
            return true;
        }

        double value;
        try {
            value = Double.parseDouble(args[2].replace(",", "."));
        } catch (NumberFormatException ex) {
            s.sendMessage(Component.text("Preço inválido: " + args[2], NamedTextColor.RED));
            return true;
        }
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            s.sendMessage(Component.text("O preço precisa ser 0 ou maior.", NamedTextColor.RED));
            return true;
        }

        if (target instanceof BookRef br) {
            bookPrices.put(br.key() + ":" + br.level(), value);
            custom.set("books." + br.key() + "." + br.level(), value);
            saveCustom();
            s.sendMessage(Component.text(bookName(br) + " agora vende por " + money(value) + " (só esse livro, os outros não mudam).", NamedTextColor.GREEN));
        } else if (target instanceof String key) {
            setSpecial(key, value);
            s.sendMessage(Component.text(key + " agora vende por " + money(value) + ".", NamedTextColor.GREEN));
        } else {
            Material m = (Material) target;
            if (m == Material.BOW || m == Material.CROSSBOW) {
                s.sendMessage(Component.text("Para arco/besta use: bow, bow-flame, crossbow ou crossbow-quick-charge.", NamedTextColor.RED));
                return true;
            }
            prices.put(m, value);
            custom.set("prices." + m.name(), value);
            saveCustom();
            s.sendMessage(Component.text(m.name() + " agora vende por " + money(value) + ".", NamedTextColor.GREEN));
            if (isBlocked(m)) {
                s.sendMessage(Component.text("Atenção: esse item está na lista 'blocked' do config.yml, então continua sem vender.", NamedTextColor.GOLD));
            }
        }
        return true;
    }

    /** /sell setprice book <encantamento> <nível> <preço>  |  /sell price book <encantamento> <nível> */
    private boolean handleBook(CommandSender s, String[] args, boolean set) {
        int need = set ? 5 : 4;
        if (args.length < need) {
            s.sendMessage(Component.text(set
                    ? "Use: /sell setprice book <encantamento> <nível> <preço>  (ex.: swift_sneak 3 5000)"
                    : "Use: /sell price book <encantamento> <nível>", NamedTextColor.YELLOW));
            return true;
        }
        String key = args[2].toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key)) == null) {
            s.sendMessage(Component.text("Encantamento não encontrado: " + args[2] + " (use o tab para ver a lista)", NamedTextColor.RED));
            return true;
        }
        int level;
        try {
            level = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            s.sendMessage(Component.text("Nível inválido: " + args[3], NamedTextColor.RED));
            return true;
        }
        if (level < 1) {
            s.sendMessage(Component.text("O nível precisa ser 1 ou maior.", NamedTextColor.RED));
            return true;
        }
        BookRef ref = new BookRef(key, level);
        if (!set) {
            s.sendMessage(Component.text(bookName(ref) + " vende por " + money(unitPrice(makeBook(ref))) + " cada.", NamedTextColor.GREEN));
            return true;
        }
        double value;
        try {
            value = Double.parseDouble(args[4].replace(",", "."));
        } catch (NumberFormatException ex) {
            s.sendMessage(Component.text("Preço inválido: " + args[4], NamedTextColor.RED));
            return true;
        }
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            s.sendMessage(Component.text("O preço precisa ser 0 ou maior.", NamedTextColor.RED));
            return true;
        }
        bookPrices.put(key + ":" + level, value);
        custom.set("books." + key + "." + level, value);
        saveCustom();
        s.sendMessage(Component.text(bookName(ref) + " agora vende por " + money(value) + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("price", "setprice", "venderesc", "reload"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("setprice") || args[0].equalsIgnoreCase("price"))) {
            out.add("hand");
            out.add("book");
            out.addAll(SPECIALS);
            for (Material m : Material.values())
                if (m.isItem() && !m.isAir() && !m.name().startsWith("LEGACY_")) out.add(m.name().toLowerCase(Locale.ROOT));
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("venderesc")) {
            out.add("on");
            out.add("off");
        }
        else if (args.length == 3 && args[1].equalsIgnoreCase("book")) {
            for (Enchantment en : Registry.ENCHANTMENT) out.add(en.getKey().getKey());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ---------------- eventos do menu ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getClick() == ClickType.DOUBLE_CLICK || e.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        if (e.getRawSlot() == BUTTON) {
            e.setCancelled(true);
            sell(p, top);
            refreshButton(top); // o menu continua aberto; o botão volta a mostrar $0
            return;
        }

        // atualiza o valor do botão depois que o clique for processado
        Bukkit.getScheduler().runTask(this, () -> refreshButton(top));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;
        if (e.getRawSlots().contains(BUTTON)) {
            e.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> refreshButton(top));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof SellHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        if (sellOnClose) {
            sell(p, inv);
        } else {
            // sem venda ao fechar: devolve tudo
            for (int i = 0; i < BUTTON; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null) continue;
                for (ItemStack rest : p.getInventory().addItem(it).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
                inv.setItem(i, null);
            }
        }
    }
}
