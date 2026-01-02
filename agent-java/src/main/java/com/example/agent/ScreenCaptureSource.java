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
    private int frameCount = 0;  // 帧计数器，用于性能统计
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
        
        // 使用动态间隔：根据实际处理时间调整下一次捕获的延迟
        // 这样即使单帧处理慢，也能尽量达到目标帧率
        captureTask = executor.scheduleWithFixedDelay(() -> {
            if (running) {
                try {
                    captureFrame();
                    // 成功捕获，重置错误计数
                    if (consecutiveErrors > 0) {
                        System.out.println("屏幕捕获已恢复");
                        consecutiveErrors = 0;
                    }
                } catch (Exception e) {
                    consecutiveErrors++;
                    System.err.println("捕获帧失败 (" + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + "): " + e.getMessage());
                    
                    // 如果连续失败次数过多，停止捕获避免资源浪费
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        System.err.println("连续失败次数过多，停止屏幕捕获");
                        running = false;
                    }
                }
            }
        }, 0, 1000 / frameRate, TimeUnit.MILLISECONDS);
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
     * 动态更新帧率（异步执行，避免阻塞）
     */
    public void updateFrameRate(int newFrameRate) {
        if (newFrameRate > 0 && newFrameRate != this.frameRate) {
            new Thread(() -> {
                System.out.println("更新帧率: " + this.frameRate + " -> " + newFrameRate);
                this.frameRate = newFrameRate;
                if (running) {
                    System.out.println("重新调度屏幕捕获任务以应用新帧率");
                    restartCapture();
                    System.out.println("屏幕捕获已重新调度，新帧率: " + this.frameRate);
                }
            }, "FrameRateUpdate-Thread").start();
        }
    }
    
    /**
     * 轻量级重启：只取消并重新调度任务，不关闭executor
     */
    private void restartCapture() {
        // 1. 取消当前任务
        if (captureTask != null && !captureTask.isCancelled()) {
            captureTask.cancel(false);  // 不中断正在执行的任务
            System.out.println("已取消旧的捕获任务");
        }
        
        // 2. 重置错误计数
        consecutiveErrors = 0;
        
        // 3. 使用新的帧率重新调度
        captureTask = executor.scheduleAtFixedRate(() -> {
            if (running) {
                try {
                    captureFrame();
                    // 成功捕获，重置错误计数
                    if (consecutiveErrors > 0) {
                        System.out.println("屏幕捕获已恢复");
                        consecutiveErrors = 0;
                    }
                } catch (Exception e) {
                    consecutiveErrors++;
                    System.err.println("捕获帧失败 (" + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + "): " + e.getMessage());
                    
                    // 如果连续失败次数过多，停止捕获避免资源浪费
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        System.err.println("连续失败次数过多，停止屏幕捕获");
                        running = false;
                    }
                }
            }
        }, 0, 1000 / frameRate, TimeUnit.MILLISECONDS);
        
        System.out.println("新的捕获任务已调度，间隔: " + (1000 / frameRate) + "ms");
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
                    totalTime, (t2-t1), (t4-t2), (t5-t4)
                ));
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
            if (y2 >= height) break;
            
            int rowOffset = y2 * width * 3;
            int chromaRowOffset = y * strideU;
            
            for (int x = 0; x < chromaWidth; x++) {
                int x2 = x * 2;
                if (x2 >= width) break;
                
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
