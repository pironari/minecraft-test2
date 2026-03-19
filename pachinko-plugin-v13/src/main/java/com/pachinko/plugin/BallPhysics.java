package com.pachinko.plugin;

import org.bukkit.*;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Random;

/**
 * パチンコ玉の物理シミュレーション v13.2
 * 左打ち: 盤面左上からランダムに落下 → ヘソ/チャッカー/入賞口/アウトロ
 * 右打ち: 盤面右上からランダムに落下 → 電チュー/アタッカー/入賞口/アウトロ
 */
public class BallPhysics extends BukkitRunnable {

    public enum Mode { LEFT, RIGHT }

    private static final double GRAVITY        = -0.045;
    private static final double BOUNCE_DAMPING = 0.55;
    private static final double WALL_DAMPING   = 0.45;
    private static final double FRICTION       = 0.993;
    private static final double NAIL_RADIUS    = 0.46;
    private static final int    MAX_TICKS      = 600; // 底まで流れる時間を確保

    // 落下開始Y（盤面上端付近）
    private static final double START_Y = MachineBuilder.HEIGHT - 4.0;

    // 天釘
    private static final double[][] TENPINS = {
        {3.5,23.5},{5.0,25.0},{7.0,25.8},{9.0,26.0},
        {11.0,26.0},{13.0,25.8},{15.0,25.0},{16.5,23.5},
        {5.5,22.5},{7.5,23.0},{10.0,23.2},{12.5,23.0},{14.5,22.5},
    };

    // バラ釘（左打ちモード: 右上から中央ヘソへ自然に流れる）
    private static final int[][] NAILS_LEFT = {
        {18,22},{16,22},{14,22},{12,22},
        {17,21},{15,21},{13,21},
        {18,20},{16,20},{14,20},{12,20},
        {17,19},{15,19},{13,19},
        {18,18},{16,18},{17,17},{15,17},
        {18,16},{16,16},{17,15},{15,15},
        {18,14},{16,14},{17,13},{15,13},
        {15,12},{12,12},{16,11},{13,11},
        {18,10},{16,10},{14,10},{12,10},
        {17, 9},{15, 9},{13, 9},
        {18, 8},{16, 8},{14, 8},{12, 8},
        {17, 7},{15, 7},{13, 7},{12, 7},
    };

    // バラ釘（右エリア）
    private static final int[][] NAILS_RIGHT = {
        {12,22},{14,22},{16,22},{18,22},
        {13,21},{15,21},{17,21},
        {12,20},{14,20},{16,20},{18,20},
        {13,19},{15,19},{17,19},
        {14,18},{16,18},{18,18},{15,17},{17,17},
        {14,16},{16,16},{18,16},{15,15},{17,15},
        {14,14},{16,14},{18,14},{15,13},{17,13},
        {12,12},{14,12},{16,12},{18,12},
        {13,11},{15,11},{17,11},
        {12,10},{14,10},{16,10},{18,10},
        {13, 9},{15, 9},{17, 9},
        {13, 7},{15, 7},{17, 7},
    };

    private final PachinkoPlugin plugin;
    private final PachinkoMachine machine;
    private final Location origin;
    private final Mode mode;
    private final Random random = new Random();

    private double ballX, ballY, ballVX, ballVY;
    private BlockDisplay ballDisplay;
    private boolean active = false;
    private int tickCount  = 0;

    public BallPhysics(PachinkoPlugin plugin, PachinkoMachine machine,
                       Location origin, Mode mode) {
        this.plugin  = plugin;
        this.machine = machine;
        this.origin  = origin;
        this.mode    = mode;
    }

    public void launch(double power) {
        if (active) return;

        if (mode == Mode.LEFT) {
            // 左打ち：右上(X=16〜19)から出発、左向き初速でヘソ(X=10)方向へ
            ballX  = 16.0 + random.nextDouble() * 3.0;
            ballY  = START_Y;
            ballVX = -(0.05 + random.nextDouble() * 0.07); // 左向き初速
            ballVY = -(0.03 + random.nextDouble() * 0.03);
        } else {
            // 右打ち：左上(X=1〜4)から出発、右向き初速で電チュー(X=5)/アタッカー(X=2〜8)へ
            ballX  = 1.2 + random.nextDouble() * 3.0;
            ballY  = START_Y;
            ballVX = (0.04 + random.nextDouble() * 0.06); // 右向き初速
            ballVY = -(0.03 + random.nextDouble() * 0.03);
        }

        spawnBallDisplay();
        active    = true;
        tickCount = 0;
        this.runTaskTimer(plugin, 0L, 1L);
    }

    @Override
    public void run() {
        if (!active) { cancel(); return; }
        tickCount++;
        if (tickCount > MAX_TICKS) { despawn(); return; }

        ballVY += GRAVITY;
        ballVX *= FRICTION;
        ballX  += ballVX;
        ballY  += ballVY;

        // ─── 左右壁 ──────────────────────────────
        double leftWall  = 1.2;
        double rightWall = (double)(MachineBuilder.WIDTH - 1.5);
        // 下部の丸み
        if (ballY <= 3.0) {
            double inset = (3.0 - ballY) * 0.7;
            leftWall  = Math.max(leftWall,  1.2 + inset);
            rightWall = Math.min(rightWall, (MachineBuilder.WIDTH - 1.5) - inset);
        }
        if (ballX <= leftWall)  { ballX = leftWall;  ballVX =  Math.abs(ballVX) * WALL_DAMPING; }
        if (ballX >= rightWall) { ballX = rightWall; ballVX = -Math.abs(ballVX) * WALL_DAMPING; }

        // ─── 底面レール（Y=2.0） ──────────────────
        // 玉が底まで落ちたら消えずに底レールを転がって9番排出口(X=10,Y=2)へ向かう
        final double FLOOR_Y    = 2.0;
        final double DRAIN_X    = MachineBuilder.HESO_X; // X=10 中央排出口
        final double FLOOR_FRIC = 0.94;
        if (ballY <= FLOOR_Y) {
            ballY  = FLOOR_Y;
            if (ballVY < 0) ballVY = 0;
            // 中央(X=10)に向かって引き寄せる
            double toDrain = DRAIN_X - ballX;
            if (Math.abs(toDrain) > 0.15) {
                ballVX += Math.signum(toDrain) * 0.025;
            }
            ballVX *= FLOOR_FRIC;
            // 9番排出口に到達
            if (Math.abs(ballX - DRAIN_X) < 0.5) {
                playSound(Sound.BLOCK_STONE_HIT, 0.3f, 0.5f);
                origin.getWorld().spawnParticle(Particle.SMOKE, getWorldLoc(), 4, 0.1, 0.1, 0.1, 0.01);
                machine.onHazureEnter();
                despawn();
                return;
            }
        }

        // ─── ヘソ直下チェック（Y=4付近で中央に来たら吸い込む） ────
        if (ballY <= MachineBuilder.HESO_UNDER_Y + 0.4
         && ballY >= MachineBuilder.HESO_UNDER_Y - 0.4
         && Math.abs(ballX - MachineBuilder.HESO_UNDER_X) < 0.5) {
            playSound(Sound.BLOCK_STONE_HIT, 0.2f, 0.8f);
            machine.onHazureEnter();
            despawn();
            return;
        }

        checkTenpinCollision();
        checkNailCollision();
        if (checkPrizeHoles()) return;
        updateDisplay();
    }

    private void checkTenpinCollision() {
        for (double[] n : TENPINS) {
            double dx = ballX - n[0], dy = ballY - n[1];
            double dist = Math.sqrt(dx*dx + dy*dy);
            if (dist < NAIL_RADIUS && dist > 0.01) { resolveNail(dx, dy, dist, 0.18f); break; }
        }
    }

    private void checkNailCollision() {
        int[][] nails = (mode == Mode.LEFT) ? NAILS_LEFT : NAILS_RIGHT;
        for (int[] n : nails) {
            double dx = ballX - n[0], dy = ballY - n[1];
            double dist = Math.sqrt(dx*dx + dy*dy);
            if (dist < NAIL_RADIUS && dist > 0.01) { resolveNail(dx, dy, dist, 0.14f); break; }
        }
    }

    private void resolveNail(double dx, double dy, double dist, float vol) {
        double nx = dx/dist, ny = dy/dist;
        double speed = Math.sqrt(ballVX*ballVX + ballVY*ballVY);
        double rand  = (random.nextDouble() - 0.5) * 0.30;
        ballVX = (nx + rand) * speed * BOUNCE_DAMPING;
        ballVY = ny * speed * BOUNCE_DAMPING;
        if (ballVY > 0.15) ballVY = 0.15;
        double overlap = NAIL_RADIUS * 1.1 - dist;
        ballX += nx * overlap;
        ballY += ny * overlap;
        playSound(Sound.BLOCK_STONE_HIT, vol, 1.3f + (float)(random.nextDouble()*0.9));
    }

    private boolean checkPrizeHoles() {
        return (mode == Mode.LEFT) ? checkLeftPrizes() : checkRightPrizes();
    }

    private boolean checkLeftPrizes() {
        // ヘソ（始動口）半径0.40
        if (near(ballX, ballY, MachineBuilder.HESO_X, MachineBuilder.HESO_Y, HESO_R)) {
            playSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.6f);
            origin.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, getWorldLoc(), 15, 0.3, 0.3, 0.1, 0.05);
            machine.onHesoEnter(); despawn(); return true;
        }
        // チャッカー 半径0.40
        if (near(ballX, ballY, MachineBuilder.CHAKKA_X, MachineBuilder.CHAKKA_Y, CHAKKA_R)) {
            playSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
            origin.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, getWorldLoc(), 10, 0.2, 0.2, 0.1, 0.05);
            machine.onChakkaEnter(); despawn(); return true;
        }
        // 一般入賞口 半径0.30（1マス）
        for (int[] p : MachineBuilder.PRIZE_HOLES_LEFT) {
            if (near(ballX, ballY, p[0], p[1], PRIZE_R)) {
                playSound(Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
                origin.getWorld().spawnParticle(Particle.COMPOSTER, getWorldLoc(), 8, 0.2, 0.2, 0.1, 0.05);
                machine.onPrizeEnter(); despawn(); return true;
            }
        }
        // アウトロ穴 半径0.30
        for (int[] h : MachineBuilder.HAZURE_HOLES) {
            if (near(ballX, ballY, h[0], h[1], PRIZE_R)) {
                playSound(Sound.BLOCK_GLASS_BREAK, 0.4f, 1.2f);
                machine.onHazureEnter(); despawn(); return true;
            }
        }
        return false;
    }

    private boolean checkRightPrizes() {
        // 電動チューリップ（開放中のみ）
        if (machine.isDenchOpen() && near(ballX, ballY, MachineBuilder.DENCH_X, MachineBuilder.DENCH_Y, CHAKKA_R)) {
            playSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.8f);
            origin.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, getWorldLoc(), 15, 0.3, 0.3, 0.1, 0.05);
            machine.onDenchEnter(); despawn(); return true;
        }
        // 大入賞口（アタッカー）開放中のみ
        if (machine.isAttackerOpen()
         && ballY <= MachineBuilder.ATTACKER_Y + 1.5
         && ballY >= MachineBuilder.ATTACKER_Y - 1.5
         && ballX >= MachineBuilder.ATTACKER_X_START - 1.0
         && ballX <= MachineBuilder.ATTACKER_X_END   + 1.0) {
            playSound(Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
            origin.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, getWorldLoc(), 20, 0.3, 0.3, 0.1, 0.05);
            machine.onAttackerEnter(); despawn(); return true;
        }
        // 右側一般入賞口（常時）
        for (int[] p : MachineBuilder.PRIZE_HOLES_RIGHT) {
            if (near(ballX, ballY, p[0], p[1], PRIZE_R)) {
                playSound(Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
                machine.onPrizeEnter(); despawn(); return true;
            }
        }
        // アウトロ穴
        for (int[] h : MachineBuilder.HAZURE_HOLES) {
            if (near(ballX, ballY, h[0], h[1], PRIZE_R)) {
                playSound(Sound.BLOCK_GLASS_BREAK, 0.4f, 1.2f);
                machine.onHazureEnter(); despawn(); return true;
            }
        }
        return false;
    }

    private boolean near(double x, double y, double tx, double ty, double r) {
        return Math.abs(x - tx) < r && Math.abs(y - ty) < r;
    }

    // 通常入賞口は0.30（1マス以下に絞る）
    private static final double PRIZE_R   = 0.35;
    // ヘソ・電チューは少しだけ広め（0.40）
    private static final double HESO_R    = 1.5; // ヘソは3マス幅
    private static final double CHAKKA_R  = 0.45; // チャッカーは小さめ
    // アタッカーは横幅が広いのでX方向は別判定

    private void spawnBallDisplay() {
        Location loc = getWorldLoc();
        ballDisplay = loc.getWorld().spawn(loc, BlockDisplay.class, d -> {
            d.setBlock(Material.LIGHT_GRAY_CONCRETE.createBlockData());
            d.setTransformation(new Transformation(
                new Vector3f(-0.18f, -0.18f, -0.04f), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(0.36f, 0.36f, 0.08f),    new AxisAngle4f(0, 0, 0, 1)));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGravity(false);
            d.setInvulnerable(true);
        });
    }

    private void updateDisplay() {
        if (ballDisplay == null || ballDisplay.isDead()) return;
        ballDisplay.teleport(getWorldLoc());
    }

    private void despawn() {
        active = false;
        if (ballDisplay != null && !ballDisplay.isDead()) { ballDisplay.remove(); ballDisplay = null; }
        cancel();
    }

    private Location getWorldLoc() { return origin.clone().add(ballX, ballY, 0.5); }
    private void playSound(Sound s, float vol, float pitch) {
        origin.getWorld().playSound(getWorldLoc(), s, vol, pitch);
    }

    public boolean isActive() { return active; }
    public Mode    getMode()  { return mode; }
}
