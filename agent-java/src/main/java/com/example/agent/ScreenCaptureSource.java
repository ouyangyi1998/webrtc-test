package com.example.agent;

import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class ScreenCaptureSource {
    private final CustomVideoSource videoSource;
    private final Robot robot;
    private final Rectangle screenRect;
    private final ScheduledExecutorService executor;
    private volatile boolean running;
    private final int frameRate = 15; // 15 FPS

    ScreenCaptureSource() throws AWTException {
        this.videoSource = new CustomVideoSource();
        this.robot = new Robot();
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        this.screenRect = new Rectangle(screenSize);
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    CustomVideoSource getVideoSource() {
        return videoSource;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        
        // 每隔 1000/frameRate 毫秒捕获一帧
        executor.scheduleAtFixedRate(() -> {
            if (running) {
                try {
                    captureFrame();
                } catch (Exception e) {
                    System.err.println("捕获帧失败: " + e.getMessage());
                }
            }
        }, 0, 1000 / frameRate, TimeUnit.MILLISECONDS);
    }

    void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
        if (videoSource != null) {
            videoSource.dispose();
        }
    }

    private void captureFrame() {
        try {
            // 捕获屏幕
            BufferedImage screenshot = robot.createScreenCapture(screenRect);
            
            // 转换为 I420 格式
            NativeI420Buffer i420Buffer = convertToI420(screenshot);
            
            // 创建 VideoFrame
            VideoFrame frame = new VideoFrame(i420Buffer, 0, System.nanoTime());
            
            // 推送帧到 WebRTC
            videoSource.pushFrame(frame);
            
            // 释放缓冲区资源
            i420Buffer.release();
            
        } catch (Exception e) {
            System.err.println("处理帧失败: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
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
        
        // 获取 Y, U, V 平面的 ByteBuffer（可能是直接缓冲区）
        java.nio.ByteBuffer yBuffer = i420Buffer.getDataY();
        java.nio.ByteBuffer uBuffer = i420Buffer.getDataU();
        java.nio.ByteBuffer vBuffer = i420Buffer.getDataV();
        
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        
        // RGB 转 YUV 并直接写入 ByteBuffer
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgbIndex = (y * width + x) * 3;
                int b = rgbData[rgbIndex] & 0xFF;
                int green = rgbData[rgbIndex + 1] & 0xFF;
                int r = rgbData[rgbIndex + 2] & 0xFF;
                
                // RGB 到 Y（使用标准 BT.601 公式）
                int yVal = ((66 * r + 129 * green + 25 * b + 128) >> 8) + 16;
                yVal = Math.max(0, Math.min(255, yVal));
                
                // 写入 Y 平面（考虑 stride）
                int yIndex = y * strideY + x;
                yBuffer.put(yIndex, (byte) yVal);
            }
        }
        
        // 转换 U 和 V 平面（4:2:0 子采样）
        for (int y = 0; y < chromaHeight; y++) {
            for (int x = 0; x < chromaWidth; x++) {
                // 从原始图像的 2x2 块中采样
                int x2 = x * 2;
                int y2 = y * 2;
                
                // 取 2x2 块的左上角像素
                if (x2 < width && y2 < height) {
                    int rgbIndex = (y2 * width + x2) * 3;
                    int b = rgbData[rgbIndex] & 0xFF;
                    int green = rgbData[rgbIndex + 1] & 0xFF;
                    int r = rgbData[rgbIndex + 2] & 0xFF;
                    
                    // RGB 到 U 和 V
                    int uVal = ((-38 * r - 74 * green + 112 * b + 128) >> 8) + 128;
                    int vVal = ((112 * r - 94 * green - 18 * b + 128) >> 8) + 128;
                    
                    uVal = Math.max(0, Math.min(255, uVal));
                    vVal = Math.max(0, Math.min(255, vVal));
                    
                    // 写入 U 和 V 平面（考虑 stride）
                    int chromaIndex = y * strideU + x;
                    uBuffer.put(chromaIndex, (byte) uVal);
                    vBuffer.put(chromaIndex, (byte) vVal);
                }
            }
        }
        
        return i420Buffer;
    }
}
