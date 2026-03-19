package com.pachinko.plugin;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PachinkoCommand implements CommandExecutor {

    private final PachinkoPlugin plugin;
    private final PachinkoMachine machine;

    public PachinkoCommand(PachinkoPlugin plugin, PachinkoMachine machine) {
        this.plugin = plugin;
        this.machine = machine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("プレイヤーのみ使用可能です。");
            return true;
        }
        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "pstart"   -> handlePStart(player, args);
            case "pstop"    -> handlePStop(player);
            case "pachinko" -> handleAdmin(player, args);
        }
        return true;
    }

    // ─────────────────────────────────────────────
    //  /pstart [玉数]
    // ─────────────────────────────────────────────
    private void handlePStart(Player player, String[] args) {
        // 引数ありの場合は玉購入
        if (args.length > 0) {
            // セッションが無ければ先に開始
            if (machine.getCurrentPlayer() == null || !machine.getCurrentPlayer().equals(player)) {
                if (!machine.startSession(player)) return;
            }
            // 玉購入処理
            try {
                int ballCount = Integer.parseInt(args[0]);
                plugin.getEconomyManager().purchaseBalls(player, ballCount);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "数字を入力してください。例: /pstart 500");
            }
            return;
        }

        // 引数なし: セッション中なら購入メニュー、そうでなければセッション開始＋購入メニュー
        if (machine.getCurrentPlayer() != null && machine.getCurrentPlayer().equals(player)) {
            showBuyMenu(player);
            return;
        }
        if (!machine.startSession(player)) return;
        showBuyMenu(player);
    }

    // ─────────────────────────────────────────────
    //  /pstop
    // ─────────────────────────────────────────────
    private void handlePStop(Player player) {
        machine.stopSession(player);
    }

    // ─────────────────────────────────────────────
    //  /pachinko <place|remove|info|give>
    // ─────────────────────────────────────────────
    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("pachinko.admin")) {
            player.sendMessage(ChatColor.RED + "権限がありません。");
            return;
        }
        if (args.length == 0) {
            sendAdminHelp(player);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "place" -> {
                if (machine.hasMachine()) {
                    player.sendMessage(ChatColor.RED + "既に台が設置されています。先に /pachinko remove してください。");
                    return;
                }
                if (machine.placeMachine(player.getLocation())) {
                    player.sendMessage(ChatColor.GREEN + "PachinkoWorld 台を設置しました！");
                } else {
                    player.sendMessage(ChatColor.RED + "設置に失敗しました。");
                }
            }
            case "remove" -> {
                if (machine.removeMachine()) {
                    player.sendMessage(ChatColor.GREEN + "PachinkoWorld 台を撤去しました。");
                } else {
                    player.sendMessage(ChatColor.RED + "台が設置されていません。");
                }
            }
            case "info" -> {
                if (!machine.hasMachine()) {
                    player.sendMessage(ChatColor.RED + "台が設置されていません。");
                    return;
                }
                player.sendMessage(ChatColor.GOLD + "PachinkoWorld 台情報:");
                player.sendMessage(ChatColor.WHITE + "  場所: " + machine.getMachineOrigin());
                Player cp = machine.getCurrentPlayer();
                player.sendMessage(ChatColor.WHITE + "  プレイ中: " + (cp != null ? cp.getName() : "なし"));
                player.sendMessage(ChatColor.WHITE + "  確変: " + (machine.isKakuhen() ? "ON" : "OFF"));
                player.sendMessage(ChatColor.WHITE + "  アタッカー: " + (machine.isAttackerOpen() ? "開放中" : "閉"));
            }
            case "give" -> {
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "使い方: /pachinko give <プレイヤー名> <玉数>");
                    return;
                }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "プレイヤーが見つかりません。");
                    return;
                }
                try {
                    int amount = Integer.parseInt(args[2]);
                    plugin.getEconomyManager().addBalls(target, amount);
                    player.sendMessage(ChatColor.GREEN + target.getName() + " に " + amount + "玉 付与しました。");
                    target.sendMessage(ChatColor.YELLOW + player.getName() + " から " + amount + "玉 もらいました！");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "数字を入力してください。");
                }
            }
            default -> sendAdminHelp(player);
        }
    }

    // ─────────────────────────────────────────────
    //  購入メニュー表示
    // ─────────────────────────────────────────────
    private void showBuyMenu(Player player) {
        int balls = plugin.getEconomyManager().getBalls(player);
        double balance = PachinkoPlugin.getEconomy().getBalance(player);

        player.sendMessage(ChatColor.GOLD + "══════════════════════");
        player.sendMessage(ChatColor.YELLOW + "  玉購入メニュー");
        player.sendMessage(ChatColor.WHITE + "  所持金: " + ChatColor.GREEN + String.format("¥%,.0f", balance));
        player.sendMessage(ChatColor.WHITE + "  持ち玉: " + ChatColor.AQUA + balls + "玉");
        player.sendMessage(ChatColor.GOLD + "──────────────────────");
        player.sendMessage(ChatColor.YELLOW + "  /pstart 500  " + ChatColor.WHITE + "→  500玉  ¥1,000");
        player.sendMessage(ChatColor.YELLOW + "  /pstart 1500 " + ChatColor.WHITE + "→ 1,500玉 ¥2,500 " + ChatColor.GREEN + "(お得)");
        player.sendMessage(ChatColor.YELLOW + "  /pstart 5000 " + ChatColor.WHITE + "→ 5,000玉 ¥7,000 " + ChatColor.GOLD + "(さらにお得)");
        player.sendMessage(ChatColor.GRAY + "  ハンドル(金ブロック)を右クリックで発射！");
        player.sendMessage(ChatColor.GOLD + "══════════════════════");
    }

    private void sendAdminHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "PachinkoWorld 管理コマンド:");
        player.sendMessage(ChatColor.YELLOW + "  /pachinko place        " + ChatColor.WHITE + "- 足元に台を設置");
        player.sendMessage(ChatColor.YELLOW + "  /pachinko remove       " + ChatColor.WHITE + "- 台を撤去");
        player.sendMessage(ChatColor.YELLOW + "  /pachinko info         " + ChatColor.WHITE + "- 台の情報表示");
        player.sendMessage(ChatColor.YELLOW + "  /pachinko give <名前> <玉数> " + ChatColor.WHITE + "- 玉を付与");
    }
}
