package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ScreenCaptureSource {
    private final CustomVideoSource videoSource;
    private final Robot robot;
    private final Rectangle screenRect;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> captureTask;
    private volatile boolean running;
    private volatile int frameRate = 15; // 默认15 FPS，可动态调整
    private int consecutiveErrors = 0;
    private int frameCount = 0; // 帧计数器，用于性能统计
    private static final int MAX_CONSECUTIVE_ERRORS = 10;

    public ScreenCaptureSource() throws AWTException {
        this.videoSource = new CustomVideoSource();
        this.robot = new Robot();
        // 关闭Robot的自动延迟以提高捕获速度
        this.robot.setAutoDelay(0);
        // 设置更快的等待时间
        this.robot.setAutoWaitForIdle(false);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        this.screenRect = new Rectangle(screenSize);

        System.out.println(String.format("屏幕捕获区域: %dx%d (全屏)",
                screenSize.width, screenSize.height));

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ScreenCapture-Thread");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CustomVideoSource getVideoSource() {
        return videoSource;
    }

    public void start() {
        if (running) {
            System.out.println("屏幕捕获已在运行中");
            return;
        }
        running = true;
        consecutiveErrors = 0;

        System.out.println("启动屏幕捕获，帧率: " + frameRate + " FPS");

        // 使用自适应调度：每次捕获完成后根据当前帧率计算下次延迟
        // 这样帧率变化会立即生效，无需重启任务
        scheduleNextCapture();
    }

    /**
     * 调度下一次捕获（自适应间隔）
     */
    private void scheduleNextCapture() {
        if (!running || executor == null || executor.isShutdown()) {
            return;
        }

        long intervalMs = 1000 / frameRate;
        captureTask = executor.schedule(() -> {
            if (running) {
                long startTime = System.currentTimeMillis();
                try {
                    captureFrame();
                    if (consecutiveErrors > 0) {
                        System.out.println("屏幕捕获已恢复");
                        consecutiveErrors = 0;
                    }
                } catch (Exception e) {
                    consecutiveErrors++;
                    System.err.println(
                            "捕获帧失败 (" + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + "): " + e.getMessage());

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        System.err.println("连续失败次数过多，停止屏幕捕获");
                        running = false;
                        return;
                    }
                }

                // 计算下次捕获的延迟（减去本次捕获耗时，保持目标帧率）
                long elapsed = System.currentTimeMillis() - startTime;
                long nextDelay = Math.max(1, intervalMs - elapsed);

                // 调度下一次捕获
                scheduleNextCapture();
            }
        }, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        System.out.println("停止屏幕捕获...");
        running = false;

        if (captureTask != null && !captureTask.isCancelled()) {
            captureTask.cancel(false);
            captureTask = null;
        }

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    System.err.println("Executor 未能及时终止，强制关闭");
                    executor.shutdownNow();
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        System.err.println("Executor 无法终止");
                    }
                }
            } catch (InterruptedException e) {
                System.err.println("等待 Executor 终止时被中断");
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (videoSource != null) {
            try {
                videoSource.dispose();
                System.out.println("VideoSource 已释放");
            } catch (Exception e) {
                System.err.println("释放 VideoSource 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 动态更新帧率（平滑过渡，不中断视频流）
     * 由于使用自适应调度，只需更新变量，下次捕获会自动使用新帧率
     */
    public void updateFrameRate(int newFrameRate) {
        if (newFrameRate > 0 && newFrameRate != this.frameRate) {
            System.out.println("更新帧率: " + this.frameRate + " -> " + newFrameRate + " FPS");
            this.frameRate = newFrameRate;
            // 无需重启任务，下次调度会自动使用新帧率
        }
    }

    private void captureFrame() {
        long startTime = System.currentTimeMillis();
        NativeI420Buffer i420Buffer = null;
        try {
            // 1. 捕获屏幕
            long t1 = System.currentTimeMillis();
            BufferedImage screenshot = robot.createScreenCapture(screenRect);
            long t2 = System.currentTimeMillis();
            if (screenshot == null) {
                throw new RuntimeException("屏幕截图返回 null");
            }

            // 2. 转换为 I420 格式
            i420Buffer = convertToI420(screenshot);
            long t4 = System.currentTimeMillis();
            if (i420Buffer == null) {
                throw new RuntimeException("I420 转换返回 null");
            }

            // 3. 创建 VideoFrame
            VideoFrame frame = new VideoFrame(i420Buffer, 0, System.nanoTime());

            // 4. 推送帧到 WebRTC
            videoSource.pushFrame(frame);
            long t5 = System.currentTimeMillis();

            long totalTime = t5 - startTime;
            // 每30帧输出一次性能统计
            if (frameCount++ % 30 == 0) {
                System.out.println(String.format(
                        "[性能] 总耗时=%dms (捕获=%dms, I420=%dms, 推送=%dms)",
                        totalTime, (t2 - t1), (t4 - t2), (t5 - t4)));
            }

        } catch (Exception e) {
            System.err.println("处理帧失败: " + e.getClass().getName() + " - " + e.getMessage());
            throw e; // 重新抛出以便上层处理
        } finally {
            // 确保释放缓冲区资源
            if (i420Buffer != null) {
                try {
                    i420Buffer.release();
                } catch (Exception e) {
                    System.err.println("释放 I420 缓冲区失败: " + e.getMessage());
                }
            }
        }
    }

    private NativeI420Buffer convertToI420(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        // 将 BufferedImage 转换为 RGB
        BufferedImage rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = rgbImage.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();

        byte[] rgbData = ((DataBufferByte) rgbImage.getRaster().getDataBuffer()).getData();

        // 创建 I420 缓冲区
        NativeI420Buffer i420Buffer = NativeI420Buffer.allocate(width, height);

        // 获取 stride（每行的字节数，可能包含填充）
        int strideY = i420Buffer.getStrideY();
        int strideU = i420Buffer.getStrideU();
        int strideV = i420Buffer.getStrideV();

        // 获取 Y, U, V 平面的 ByteBuffer
        java.nio.ByteBuffer yBuffer = i420Buffer.getDataY();
        java.nio.ByteBuffer uBuffer = i420Buffer.getDataU();
        java.nio.ByteBuffer vBuffer = i420Buffer.getDataV();

        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;

        // 性能优化：使用批量操作和减少边界检查
        // 转换 Y 平面
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width * 3;
            int yRowOffset = y * strideY;

            for (int x = 0; x < width; x++) {
                int rgbIndex = rowOffset + x * 3;
                int b = rgbData[rgbIndex] & 0xFF;
                int g = rgbData[rgbIndex + 1] & 0xFF;
                int r = rgbData[rgbIndex + 2] & 0xFF;

                // RGB 到 Y（使用标准 BT.601 公式，优化计算）
                int yVal = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yVal = yVal < 0 ? 0 : (yVal > 255 ? 255 : yVal);

                yBuffer.put(yRowOffset + x, (byte) yVal);
            }
        }

        // 转换 U 和 V 平面（4:2:0 子采样，每隔一行一列采样）
        for (int y = 0; y < chromaHeight; y++) {
            int y2 = y * 2;
            if (y2 >= height)
                break;

            int rowOffset = y2 * width * 3;
            int chromaRowOffset = y * strideU;

            for (int x = 0; x < chromaWidth; x++) {
                int x2 = x * 2;
                if (x2 >= width)
                    break;

                int rgbIndex = rowOffset + x2 * 3;
                int b = rgbData[rgbIndex] & 0xFF;
                int g = rgbData[rgbIndex + 1] & 0xFF;
                int r = rgbData[rgbIndex + 2] & 0xFF;

                // RGB 到 U 和 V
                int uVal = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int vVal = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                uVal = uVal < 0 ? 0 : (uVal > 255 ? 255 : uVal);
                vVal = vVal < 0 ? 0 : (vVal > 255 ? 255 : vVal);

                uBuffer.put(chromaRowOffset + x, (byte) uVal);
                vBuffer.put(chromaRowOffset + x, (byte) vVal);
            }
        }

        return i420Buffer;
    }
}
