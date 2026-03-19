package com.pachinko.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * パチンコ台メイン制御
 * 左打ち（通常）→ ヘソ入賞 → ルーレット → 大当たりなら右打ちモード
 */
public class PachinkoMachine implements Listener {

    private final PachinkoPlugin plugin;
    private final EconomyManager economyManager;

    private Location machineOrigin = null;
    private Player   currentPlayer = null;

    private DigitalDisplay display  = null;
    private AttackerSystem attacker = null;

    // 抽選設定
    private int    normalProb;
    private int    kakuhenProb;
    private double kakuhenRate;
    private int    maxHold;

    // 状態
    private boolean isKakuhen          = false;
    private boolean isJackpotInProgress = false;
    private boolean isSpinInProgress    = false;
    private boolean isRightMode         = false; // 右打ちモード
    private boolean isDenchOpen         = false; // 電チューリップ開放中

    // 保留（当たりかどうかをDisplayに渡してDisplay側で色管理）
    private final Queue<Boolean> holdQueue = new LinkedList<>();

    // 発射
    private BukkitTask   launchTask   = null;
    private boolean      isLaunching  = false;
    private final java.util.List<BallPhysics> activeBalls = new java.util.ArrayList<>();
    private static final int LAUNCH_INTERVAL = 4; // tick間隔（1秒=20tick → 5発/秒）

    private final Random random = new Random();

    public PachinkoMachine(PachinkoPlugin plugin, EconomyManager eco) {
        this.plugin          = plugin;
        this.economyManager  = eco;
        loadConfig();
    }

    private void loadConfig() {
        FileConfiguration c = plugin.getConfig();
        normalProb   = c.getInt("pachinko.normal_probability", 99);
        kakuhenProb  = c.getInt("pachinko.kakuhen_probability", 33);
        kakuhenRate  = c.getDouble("pachinko.kakuhen_rate", 0.6);
        maxHold      = c.getInt("pachinko.hold_max", 4);
    }

    // ─────────────────────────────────────────────
    //  台の設置・撤去
    // ─────────────────────────────────────────────
    public boolean placeMachine(Location origin) {
        if (machineOrigin != null) return false;
        machineOrigin = origin.clone();
        MachineBuilder.build(machineOrigin);
        display  = new DigitalDisplay(plugin, machineOrigin, this);
        attacker = new AttackerSystem(plugin, machineOrigin, this);
        plugin.getLogger().info("PachinkoWorld 台を設置しました");
        return true;
    }

    public boolean removeMachine() {
        if (machineOrigin == null) return false;
        if (display  != null) display.cleanup();
        if (attacker != null) attacker.reset();
        MachineBuilder.remove(machineOrigin);
        machineOrigin = null;
        display  = null;
        attacker = null;
        return true;
    }

    // ─────────────────────────────────────────────
    //  セッション管理
    // ─────────────────────────────────────────────
    public boolean startSession(Player player) {
        if (machineOrigin == null) {
            player.sendMessage(ChatColor.RED + "台が設置されていません。");
            return false;
        }
        if (currentPlayer != null) {
            player.sendMessage(ChatColor.RED + "現在他のプレイヤーがプレイ中です。");
            return false;
        }
        currentPlayer      = player;
        isKakuhen          = false;
        isJackpotInProgress = false;
        isSpinInProgress    = false;
        isRightMode         = false;
        isDenchOpen         = false;
        holdQueue.clear();


        player.sendMessage(ChatColor.GOLD + "══════════════════════");
        player.sendMessage(ChatColor.YELLOW + "  ★ PachinkoWorld 開始！");
        player.sendMessage(ChatColor.WHITE + "  持ち玉: " + ChatColor.AQUA + economyManager.getBalls(player) + "玉");
        player.sendMessage(ChatColor.GRAY + "  レバーを右クリックで発射！");
        player.sendMessage(ChatColor.GOLD + "══════════════════════");
        return true;
    }

    public void stopSession(Player player) {
        if (currentPlayer == null || !currentPlayer.equals(player)) {
            player.sendMessage(ChatColor.RED + "あなたはプレイしていません。");
            return;
        }
        // 大当たり中は止められない
        if (isJackpotInProgress) {
            player.sendMessage(ChatColor.RED + "★ 大当たり中は終了できません！玉が増えるまで待ってください！");
            return;
        }
        stopLaunching();
        economyManager.exchangeBalls(player);
        currentPlayer = null;
        holdQueue.clear();
        isRightMode = false;
    }

    // ─────────────────────────────────────────────
    //  発射システム
    // ─────────────────────────────────────────────
    public void startLaunching(Player player) {
        if (currentPlayer == null || !currentPlayer.equals(player)) return;
        if (isLaunching) return;
        isLaunching = true;

        launchTask = new BukkitRunnable() {
            int tick = 0;
            @Override
            public void run() {
                tick++;
                if (!isLaunching || currentPlayer == null || !currentPlayer.isOnline()) {
                    stopLaunching(); cancel(); return;
                }
                if (tick % LAUNCH_INTERVAL == 0) fireBall();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void stopLaunching() {
        isLaunching = false;
        if (launchTask != null) { launchTask.cancel(); launchTask = null; }
    }

    private void fireBall() {
        if (currentPlayer == null) return;
        if (!economyManager.removeBalls(currentPlayer, 1)) {
            if (isJackpotInProgress) {
                // 大当たり中は玉切れでも止めない（アタッカーで増えるはず）
                // 玉0でも数発は撃てるように一時的に1玉付与
                economyManager.addBalls(currentPlayer, 3);
                currentPlayer.sendMessage(ChatColor.RED + "⚠ 玉が少ない！アタッカーに入れて増やしてください！");
            } else {
                currentPlayer.sendMessage(ChatColor.RED + "玉が足りません！/pstop で換金 or /pstart 500 で購入");
                stopLaunching();
                return;
            }
        }
        // 最大同時飛行数を8に制限
        activeBalls.removeIf(b -> !b.isActive());
        if (activeBalls.size() >= 8) return;

        double power = 0.4 + random.nextDouble() * 0.4;
        BallPhysics.Mode mode = isRightMode ? BallPhysics.Mode.RIGHT : BallPhysics.Mode.LEFT;
        BallPhysics ball = new BallPhysics(plugin, this, machineOrigin, mode);
        ball.launch(power);
        activeBalls.add(ball);

        // 残り玉表示（50玉ごと or 残り少ない時）
        int balls = economyManager.getBalls(currentPlayer);
        if (balls % 50 == 0 || balls <= 20) {
            int hc = (display != null) ? display.getHoldCount() : 0;
            currentPlayer.sendMessage(ChatColor.YELLOW + "持ち玉: " + ChatColor.AQUA + balls + "玉"
                + (isKakuhen ? ChatColor.RED + "  【確変】" : "")
                + (isRightMode ? ChatColor.GREEN + "  【右打ち】" : "")
                + (hc > 0 ? ChatColor.AQUA + "  保留:" + hc : ""));
        }
    }

    // ─────────────────────────────────────────────
    //  入賞処理
    // ─────────────────────────────────────────────
    public void onHesoEnter() {
        if (currentPlayer == null) return;
        economyManager.addBalls(currentPlayer, 3);
        currentPlayer.sendMessage(ChatColor.YELLOW + "★ ヘソ入賞！(+3玉)");

        if (isJackpotInProgress || isSpinInProgress) {
            addHold();
            return;
        }
        doLottery();
    }

    public void onHazureEnter() {
        // アウトロ - 何もしない（玉が消えるだけ）
    }

    /** チャッカー（左側入賞口）入賞 */
    public void onChakkaEnter() {
        if (currentPlayer == null) return;
        economyManager.addBalls(currentPlayer, 4);
        currentPlayer.sendMessage(ChatColor.GREEN + "◆ チャッカー入賞！(+4玉)");
        currentPlayer.playSound(currentPlayer.getLocation(),
            Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
    }

    /** 電動チューリップ入賞 */
    public void onDenchEnter() {
        if (currentPlayer == null) return;
        economyManager.addBalls(currentPlayer, 3);
        currentPlayer.sendMessage(ChatColor.AQUA + "♦ 電チュー入賞！(+3玉)");
        currentPlayer.playSound(currentPlayer.getLocation(),
            Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.8f);
        // 右打ち中に電チュー入賞 → 保留追加（ヘソ代わり）
        if (isRightMode) {
            if (isJackpotInProgress || isSpinInProgress) {
                addHold();
            } else {
                doLottery();
            }
        }
    }

    /** 電チューリップの開閉を設定（AttackerSystemから呼ぶ） */
    public void setDenchOpen(boolean open) {
        this.isDenchOpen = open;
        if (currentPlayer != null) {
            if (open) {
                currentPlayer.sendMessage(ChatColor.AQUA + "  電チューリップ 開放！");
            }
        }
    }

    public void onAttackerEnter() {
        if (attacker != null) attacker.onBallEnter();
    }

    public void onPrizeEnter() {
        if (currentPlayer == null) return;
        economyManager.addBalls(currentPlayer, 5);
        currentPlayer.sendMessage(ChatColor.GREEN + "入賞！(+5玉)");
    }

    public void onJackpotEnd(boolean wasKakuhen) {
        isJackpotInProgress = false;
        stopLaunching();

        // 確変判定
        isKakuhen = wasKakuhen && (random.nextDouble() < kakuhenRate);
        if (isKakuhen) {
            // 確変：右打ち継続で自動発射
            isRightMode = true;
            currentPlayer.sendMessage(ChatColor.RED + "★ 確変突入！右打ち継続！");
            if (display != null) display.setKakuhenMode(true);
        } else {
            // 通常：左打ちに戻して自動発射
            isRightMode = false;
            currentPlayer.sendMessage(ChatColor.WHITE + "◀ 通常モード。左打ちに戻します。");
            if (display != null) display.setKakuhenMode(false);
        }

        // 少し待ってから自動発射再開
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (currentPlayer != null && currentPlayer.isOnline()) {
                startLaunching(currentPlayer);
                String msg = isRightMode
                    ? ChatColor.GREEN + "▶ 右打ち自動発射再開"
                    : ChatColor.GREEN + "▶ 左打ち自動発射再開";
                currentPlayer.sendMessage(msg);
            }
        }, 40L);

        // 保留消化
        plugin.getServer().getScheduler().runTaskLater(plugin, this::consumeHold, 30L);
    }

    // ─────────────────────────────────────────────
    //  抽選ロジック
    // ─────────────────────────────────────────────
    private void doLottery() {
        isSpinInProgress = true;
        int prob    = isKakuhen ? kakuhenProb : normalProb;
        boolean hit = (random.nextInt(prob) == 0);
        boolean smallHit = !hit && (random.nextInt(100) == 0);

        int[] targets;
        if (hit) {
            int n = random.nextInt(8);
            targets = new int[]{n, n, n};
        } else if (smallHit) {
            int n = random.nextInt(8);
            int m = (n + 1 + random.nextInt(7)) % 8;
            targets = new int[]{n, m, n};
        } else {
            targets = makeHazureValues();
        }

        // 演出タイプを決定（保留なし=直撃は白扱い）
        DigitalDisplay.ReachType reachType = decideReachType(hit, targets);

        final boolean fHit = hit, fSmall = smallHit;
        final int[] fTargets = targets;

        if (display != null) {
            display.spin(targets, reachType, () -> {
                isSpinInProgress = false;
                onSpinComplete(fHit, fSmall, fTargets);
            });
        } else {
            isSpinInProgress = false;
        }
    }

    /**
     * 演出タイプを決定する
     * hit=true  → 当たり図柄なので最低NORMAL、保留色が高ければ上位演出
     * hit=false → ハズレ図柄、リーチ形かどうかで分岐
     */
    private DigitalDisplay.ReachType decideReachType(boolean hit, int[] targets) {
        boolean isReachShape = (targets[0] == targets[2]) && (targets[0] != targets[1]);

        if (hit) {
            // 当たりでも演出はランダム（赤が出やすい）
            int r = random.nextInt(10);
            if (r < 5) return DigitalDisplay.ReachType.SUPER_RED;
            if (r < 8) return DigitalDisplay.ReachType.SUPER;
            return DigitalDisplay.ReachType.NORMAL;
        }

        if (!isReachShape) return DigitalDisplay.ReachType.NONE; // リーチ形でなければ演出なし

        // リーチハズレ：確率でランクアップ（煽り）
        int r = random.nextInt(10);
        if (r < 1) return DigitalDisplay.ReachType.SUPER_RED; // 10%で赤煽り
        if (r < 3) return DigitalDisplay.ReachType.SUPER;     // 20%でスーパー煽り
        return DigitalDisplay.ReachType.NORMAL;               // 通常リーチ
    }

    private void onSpinComplete(boolean hit, boolean smallHit, int[] values) {
        if (currentPlayer == null) return;

        if (hit) {
            isJackpotInProgress = true;
            isRightMode         = true;
            stopLaunching();

            // 777=1500、奇数=1200、偶数=1000 ← アタッカー入賞で自然に増える
            // （1入賞15玉 × 最大150カウント = 最大2250玉）
            boolean is777 = (values[0] == 7 && values[1] == 7 && values[2] == 7);
            boolean isOdd = (values[0] % 2 != 0);
            String payLabel = is777 ? "777！！！超大当たり！" : (isOdd ? "奇数大当たり！" : "偶数大当たり！");

            currentPlayer.sendMessage(ChatColor.GOLD + "★★★ " + payLabel + "アタッカーに玉を入れろ！★★★");
            currentPlayer.sendMessage(ChatColor.YELLOW + "▶ 右打ち自動発射中！アタッカーに入るたびに玉が増えるぞ！");
            currentPlayer.playSound(currentPlayer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

            if (display != null) {
                display.playJackpotAnimation(() -> {
                    if (attacker != null) attacker.startJackpot(isKakuhen, values[0]);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (currentPlayer != null && isJackpotInProgress) {
                            startLaunching(currentPlayer);
                        }
                    }, 10L);
                });
            }
        } else if (smallHit) {
            currentPlayer.sendMessage(ChatColor.AQUA + "小当たり！アタッカーが少し開く！");
            currentPlayer.playSound(currentPlayer.getLocation(),
                Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
            if (attacker != null) attacker.startSmallHit();
            consumeHold();
        } else {
            boolean reach = (values[0] == values[2]) && (values[0] != values[1]);
            if (reach) {
                currentPlayer.sendMessage(ChatColor.GRAY + "惜しい... リーチハズレ");
            }
            consumeHold();
        }
    }

    // ─────────────────────────────────────────────
    //  保留
    // ─────────────────────────────────────────────
    private void addHold() {
        if (display == null) return;
        int holdCount = display.getHoldCount();
        if (holdCount >= maxHold) return;
        int prob = isKakuhen ? kakuhenProb : normalProb;
        boolean hit = (random.nextInt(prob) == 0);
        holdQueue.offer(hit);
        int colorLevel = display.addHold(hit);

        // 色に応じたメッセージ
        String colorMsg = switch (colorLevel) {
            case 4 -> ChatColor.RED    + "【赤保留】大チャンス！！";
            case 3 -> ChatColor.GREEN  + "【緑保留】チャンス！";
            case 2 -> ChatColor.AQUA   + "【青保留】";
            default -> ChatColor.WHITE + "【白保留】";
        };
        int newCount = display.getHoldCount();
        currentPlayer.sendMessage(ChatColor.AQUA + "保留 " + newCount + "/" + maxHold + "  " + colorMsg);
    }

    private void consumeHold() {
        if (holdQueue.isEmpty()) return;

        // 消化前に保留色を取得して演出を決める
        int holdColor = (display != null) ? display.peekFirstHoldColor() : 1;
        if (display != null) display.consumeHold();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            boolean hit = holdQueue.poll();

            // ─── 保留色による当たり昇格 ─────────────
            // 赤保留: 93%当たり（外れでも93%で強制当たり昇格）
            // 緑保留: 30%当たり昇格
            // 青保留: 5%当たり昇格
            if (!hit) {
                if (holdColor == 4 && random.nextInt(100) < 93) hit = true; // 赤93%
                else if (holdColor == 3 && random.nextInt(100) < 30) hit = true; // 緑30%
                else if (holdColor == 2 && random.nextInt(100) <  5) hit = true; // 青5%
            }

            isSpinInProgress = true;
            int[] targets;
            if (hit) {
                int n = random.nextInt(8);
                targets = new int[]{n, n, n};
            } else {
                targets = makeHazureValues();
            }

            // 保留色 + hit で演出タイプを決定
            DigitalDisplay.ReachType reachType = decideReachTypeFromHoldColor(hit, holdColor, targets);

            final boolean fHit = hit;
            final int[] fTargets = targets;
            if (display != null) {
                display.spin(targets, reachType, () -> {
                    isSpinInProgress = false;
                    onSpinComplete(fHit, false, fTargets);
                });
            }
        }, 40L);
    }

    /**
     * 保留消化時の演出タイプ（保留色を優先的に反映）
     */
    private DigitalDisplay.ReachType decideReachTypeFromHoldColor(boolean hit, int holdColor, int[] targets) {
        boolean isReachShape = (targets[0] == targets[2]) && (targets[0] != targets[1]);

        if (hit) {
            // 当たり：保留色が高いほど上位演出が確定
            return switch (holdColor) {
                case 4 -> DigitalDisplay.ReachType.SUPER_RED;           // 赤 → 必ず赤リーチ
                case 3 -> random.nextInt(3) == 0                        // 緑 → 75%スーパー
                    ? DigitalDisplay.ReachType.SUPER_RED
                    : DigitalDisplay.ReachType.SUPER;
                case 2 -> random.nextInt(2) == 0                        // 青 → 50%ノーマル/スーパー
                    ? DigitalDisplay.ReachType.SUPER
                    : DigitalDisplay.ReachType.NORMAL;
                default -> DigitalDisplay.ReachType.NORMAL;             // 白 → 通常リーチ
            };
        }

        // ハズレ：リーチ形でなければ演出なし
        if (!isReachShape) return DigitalDisplay.ReachType.NONE;

        // ハズレリーチ：保留色が高いほど派手な煽り（でも当たらない）
        return switch (holdColor) {
            case 4 -> DigitalDisplay.ReachType.SUPER_RED;   // 赤ハズレ（激アツ煽り）
            case 3 -> DigitalDisplay.ReachType.SUPER;
            case 2 -> DigitalDisplay.ReachType.NORMAL;
            default -> random.nextInt(3) == 0               // 白は1/3でリーチ
                ? DigitalDisplay.ReachType.NORMAL
                : DigitalDisplay.ReachType.NONE;
        };
    }

    // ─────────────────────────────────────────────
    //  ユーティリティ
    // ─────────────────────────────────────────────
    private int[] makeHazureValues() {
        int l = random.nextInt(10);
        int m, r;
        if (random.nextInt(4) == 0) { // 1/4でリーチ
            r = l;
            do { m = random.nextInt(10); } while (m == l);
        } else {
            do { m = random.nextInt(10); } while (m == l);
            do { r = random.nextInt(10); } while (r == l);
        }
        return new int[]{l, m, r};
    }

    private boolean isReach(int[] v) {
        return v[0] == v[2] && v[0] != v[1];
    }

    public void addBallsToPlayer(int amount) {
        if (currentPlayer != null) economyManager.addBalls(currentPlayer, amount);
    }

    // ─────────────────────────────────────────────
    //  レバーのクリック検知
    // ─────────────────────────────────────────────
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (machineOrigin == null) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        int bx = block.getX();
        int by = block.getY();
        int bz = block.getZ();
        int baseX = machineOrigin.getBlockX();
        int tz    = machineOrigin.getBlockZ();
        int baseY = machineOrigin.getBlockY();

        // Z が合わない → 関係ないブロック
        if (bz != tz) return;
        // Y が合わない
        if (by < baseY + 2 || by > baseY + 4) return;

        // 左打ちレバー (X = baseX + LEVER_LEFT_X = 右端)
        boolean isLeftLever  = (bx == baseX + MachineBuilder.LEVER_LEFT_X);
        // 右打ちレバー (X = baseX + LEVER_RIGHT_X = 左端)
        boolean isRightLever = (bx == baseX + MachineBuilder.LEVER_RIGHT_X);

        if (!isLeftLever && !isRightLever) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (currentPlayer == null) {
            player.sendMessage(ChatColor.RED + "/pstart で玉を購入してからどうぞ！");
            return;
        }
        if (!currentPlayer.equals(player)) {
            player.sendMessage(ChatColor.RED + "他のプレイヤーがプレイ中です。");
            return;
        }

        // 大当たり中は右打ち専用
        if (isJackpotInProgress && isLeftLever) {
            player.sendMessage(ChatColor.RED + "大当たり中は右打ち！右のレバーを使ってください");
            return;
        }

        if (isLeftLever) {
            // ─── 左レバー：左打ちに切り替えて発射 ───
            if (isRightMode) {
                isRightMode = false;
                stopLaunching();
                player.sendMessage(ChatColor.WHITE + "◀ 左打ちに切り替え");
            }
            if (!isLaunching) {
                startLaunching(player);
                player.sendMessage(ChatColor.GREEN + "▶ 左打ち発射中！もう一度で停止");
            } else {
                stopLaunching();
                player.sendMessage(ChatColor.YELLOW + "■ 発射停止");
            }
        } else {
            // ─── 右レバー：右打ちに切り替えて発射 ───
            if (!isRightMode) {
                isRightMode = true;
                stopLaunching();
                player.sendMessage(ChatColor.GREEN + "▶ 右打ちに切り替え！アタッカーを狙え！");
            }
            if (!isLaunching) {
                startLaunching(player);
                player.sendMessage(ChatColor.GREEN + "▶ 右打ち発射中！もう一度で停止");
            } else {
                stopLaunching();
                player.sendMessage(ChatColor.YELLOW + "■ 発射停止");
            }
        }
    }

    // ─────────────────────────────────────────────
    //  シャットダウン
    // ─────────────────────────────────────────────
    public void shutdown() {
        stopLaunching();
        if (display  != null) display.cleanup();
        if (attacker != null) attacker.reset();
    }

    // Getters
    public Location getMachineOrigin()   { return machineOrigin; }
    public boolean  hasMachine()         { return machineOrigin != null; }
    public boolean  isAttackerOpen()     { return attacker != null && attacker.isOpen(); }
    public boolean  isKakuhen()          { return isKakuhen; }
    public boolean  isRightMode()        { return isRightMode; }
    public Player   getCurrentPlayer()   { return currentPlayer; }
    public boolean  isDenchOpen()        { return isDenchOpen; }
}
