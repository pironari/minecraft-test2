package com.pachinko.plugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EconomyManager {

    private final PachinkoPlugin plugin;
    private final Economy economy;
    // プレイヤーごとの持ち玉
    private final Map<UUID, Integer> playerBalls = new HashMap<>();

    public EconomyManager(PachinkoPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    /**
     * 玉を購入する（お金を引いて玉を追加）
     */
    public boolean purchaseBalls(Player player, int ballCount) {
        // YAMLのキーは整数として読み込まれるため、configSectionから直接取得する
        org.bukkit.configuration.ConfigurationSection section =
            plugin.getConfig().getConfigurationSection("economy.ball_packs");
        int price = -1;
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int packBalls = Integer.parseInt(key);
                    if (packBalls == ballCount) {
                        price = section.getInt(key);
                        break;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        if (price < 0) {
            player.sendMessage(ChatColor.RED + "そのパックは存在しません。");
            return false;
        }
        if (!economy.has(player, price)) {
            player.sendMessage(ChatColor.RED + "残高が足りません！必要金額: " + formatMoney(price)
                    + " / 所持金: " + formatMoney((int) economy.getBalance(player)));
            return false;
        }
        economy.withdrawPlayer(player, price);
        addBalls(player, ballCount);
        player.sendMessage(ChatColor.GOLD + "✦ " + ballCount + "玉購入しました！ (-" + formatMoney(price) + ")"
                + ChatColor.YELLOW + "  所持玉: " + getBalls(player) + "玉");
        return true;
    }

    /**
     * 持ち玉を換金する
     */
    public void exchangeBalls(Player player) {
        int balls = getBalls(player);
        int minExchange = plugin.getConfig().getInt("economy.min_exchange", 100);
        if (balls < minExchange) {
            player.sendMessage(ChatColor.RED + "換金には最低" + minExchange + "玉必要です。（現在: " + balls + "玉）");
            return;
        }
        int rate = plugin.getConfig().getInt("economy.exchange_rate", 4);
        double taxRate = plugin.getConfig().getDouble("economy.tax_rate", 0.1);
        double rawAmount = (double) balls / rate;
        double tax = rawAmount * taxRate;
        double finalAmount = rawAmount - tax;

        economy.depositPlayer(player, finalAmount);
        playerBalls.put(player.getUniqueId(), 0);

        player.sendMessage(ChatColor.GOLD + "══════════════════════");
        player.sendMessage(ChatColor.YELLOW + "  換金完了！");
        player.sendMessage(ChatColor.WHITE + "  持ち玉: " + ChatColor.AQUA + balls + "玉");
        player.sendMessage(ChatColor.WHITE + "  換金額: " + ChatColor.GREEN + formatMoney((int) rawAmount));
        player.sendMessage(ChatColor.WHITE + "  手数料: " + ChatColor.RED + "-" + formatMoney((int) tax) + " (10%)");
        player.sendMessage(ChatColor.WHITE + "  受取額: " + ChatColor.GOLD + formatMoney((int) finalAmount));
        player.sendMessage(ChatColor.GOLD + "══════════════════════");
    }

    public void addBalls(Player player, int amount) {
        playerBalls.merge(player.getUniqueId(), amount, Integer::sum);
    }

    public boolean removeBalls(Player player, int amount) {
        int current = getBalls(player);
        if (current < amount) return false;
        playerBalls.put(player.getUniqueId(), current - amount);
        return true;
    }

    public int getBalls(Player player) {
        return playerBalls.getOrDefault(player.getUniqueId(), 0);
    }

    public void clearBalls(Player player) {
        playerBalls.remove(player.getUniqueId());
    }

    private String formatMoney(int amount) {
        return String.format("¥%,d", amount);
    }
}
