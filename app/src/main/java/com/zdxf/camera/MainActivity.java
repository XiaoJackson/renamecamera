package com.zdxf.camera;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.material.button.MaterialButton;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.ExposureState;
import android.util.Range;
import android.util.Size; // 注意是 android.util.Size
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraApp";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private static final String PREFS_NAME = "CameraAppPrefs";
    private static final String KEY_FILENAME = "last_filename";
    private static final String KEY_WATERMARK = "last_watermark";

    // UI 组件
    private PreviewView viewFinder;
    private ImageView ivFocusBox;
    private TextView tvOverlay;
    private TextView tvZoomDisplay;
    private TextView tvLabelSize;
    private ImageButton btnFlashOn, btnFlashOff;
    private EditText etFilename, etWatermarkText;
    private MaterialButton btnCapture, btnColor;
    private SeekBar sbSize, sbRotate; // 新增 sbRotate

    // CameraX 组件
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private Camera camera;

    // 手势与监听
    private ScaleGestureDetector cameraScaleDetector;
    private OrientationEventListener orientationEventListener;

    // 状态
    private int currentColorIndex = 0;
    private final int[] colors = {Color.WHITE, Color.RED, Color.YELLOW, Color.BLUE, Color.BLACK, Color.GREEN, Color.MAGENTA};

    private final Handler focusHandler = new Handler(Looper.getMainLooper());
    private long lastClickTime = 0;
    private int clickCount = 0;
    private boolean isFlashEnabled = false; // 用来代替直接设置 FLASH_MODE

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupUIListeners();
        loadSettings();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
        // 强制关灯，防止退出后台后灯还亮着
        if (camera != null) {
            camera.getCameraControl().enableTorch(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedFilename = prefs.getString(KEY_FILENAME, "");
        String savedWatermark = prefs.getString(KEY_WATERMARK, "");
        etFilename.setText(savedFilename);
        etWatermarkText.setText(savedWatermark);
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_FILENAME, etFilename.getText().toString());
        editor.putString(KEY_WATERMARK, etWatermarkText.getText().toString());
        editor.apply();
    }

    private void initViews() {
        viewFinder = findViewById(R.id.viewFinder);
        ivFocusBox = findViewById(R.id.ivFocusBox);
        tvOverlay = findViewById(R.id.tvOverlay);
        tvZoomDisplay = findViewById(R.id.tvZoomDisplay);

        btnFlashOn = findViewById(R.id.btnFlashOn);
        btnFlashOff = findViewById(R.id.btnFlashOff);
        btnFlashOff.setColorFilter(Color.RED);
        btnFlashOn.setColorFilter(Color.GRAY);

        etFilename = findViewById(R.id.etFilename);
        etWatermarkText = findViewById(R.id.etWatermarkText);

        btnCapture = findViewById(R.id.btnCapture);
        btnColor = findViewById(R.id.btnColor);
        sbSize = findViewById(R.id.sbSize);
        sbRotate = findViewById(R.id.sbRotate); // 初始化旋转滑条

        tvLabelSize = findViewById(R.id.tvLabelSize);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupUIListeners() {
        // 1. 相机缩放监听器 (优化版：缩放结束后自动对焦)
        cameraScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (camera == null) return false;
                ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
                if (zoomState == null) return false;
                float currentZoomRatio = zoomState.getZoomRatio();
                float scaleFactor = detector.getScaleFactor();
                camera.getCameraControl().setZoomRatio(currentZoomRatio * scaleFactor);
                return true;
            }

            // 【新增】当手指离开屏幕（缩放结束）时，强制触发一次对焦
            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                super.onScaleEnd(detector);
                // 延迟 200ms 触发，让镜头先稳定下来
                focusHandler.postDelayed(() -> {
                    // 对焦屏幕正中间
                    float centerX = viewFinder.getWidth() / 2f;
                    float centerY = viewFinder.getHeight() / 2f;
                    focusOnPoint(centerX, centerY);
                    showFocusAnimation(centerX, centerY);
                }, 200);
            }
        });

        // 2. 水印手势：【只保留单指拖拽】，彻底解决误触和乱转
        tvOverlay.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 请求父容器不要拦截触摸事件（防止和预览冲突）
                v.getParent().requestDisallowInterceptTouchEvent(true);

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - lastX;
                        float dy = event.getRawY() - lastY;
                        v.setX(v.getX() + dx);
                        v.setY(v.getY() + dy);
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        break;
                    case MotionEvent.ACTION_UP:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return true;
            }
        });

        // 3. ViewFinder 触摸监听：对焦 (优化防误触版)
        viewFinder.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private boolean isClick;
            private final int TOUCH_SLOP = 20;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                boolean scaleResult = cameraScaleDetector.onTouchEvent(event);
                if (cameraScaleDetector.isInProgress()) {
                    isClick = false;
                    return true;
                }
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        isClick = true;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        isClick = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (isClick) {
                            float dx = Math.abs(event.getX() - startX);
                            float dy = Math.abs(event.getY() - startY);
                            if (dx > TOUCH_SLOP || dy > TOUCH_SLOP) isClick = false;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            focusOnPoint(event.getX(), event.getY());
                            showFocusAnimation(event.getX(), event.getY());
                        }
                        break;
                }
                return true;
            }
        });

        // 4. 文字同步
        etWatermarkText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String text = s.toString();
                tvOverlay.setText(text);
                if (!etFilename.hasFocus()) {
                    etFilename.setText(text);
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 5. 颜色
        btnColor.setOnClickListener(v -> {
            currentColorIndex = (currentColorIndex + 1) % colors.length;
            tvOverlay.setTextColor(colors[currentColorIndex]);
        });

        // 6. 字号
        sbSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvOverlay.setTextSize(10 + progress);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 7. 【新增】旋转固定档位 (0, 90, 180, 270)
        sbRotate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // progress 是 0, 1, 2, 3
                // 角度 = progress * 90
                tvOverlay.setRotation(progress * 90f);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 8. 闪光灯
        btnFlashOn.setOnClickListener(v -> {
            isFlashEnabled = true; // 记录状态
            Toast.makeText(MainActivity.this, "闪光灯: 已开启", Toast.LENGTH_SHORT).show();
            btnFlashOn.setColorFilter(Color.YELLOW);
            btnFlashOff.setColorFilter(Color.GRAY);
        });

        btnFlashOff.setOnClickListener(v -> {
            isFlashEnabled = false; // 记录状态
            Toast.makeText(MainActivity.this, "闪光灯: 已关闭", Toast.LENGTH_SHORT).show();
            btnFlashOn.setColorFilter(Color.GRAY);
            btnFlashOff.setColorFilter(Color.RED);
        });

        // 9. 拍照
        btnCapture.setOnClickListener(v -> takePhoto());
        // 点击倍率显示文本 (tvZoomDisplay)，一键复位到 1.0x
        // 这能解决“手捏不到位导致镜头不切换”的问题
        tvZoomDisplay.setOnClickListener(v -> {
            if (camera != null) {
                camera.getCameraControl().setZoomRatio(1.0f);
                Toast.makeText(MainActivity.this, "已重置为 1.0x", Toast.LENGTH_SHORT).show();

                // 重置后也触发一次对焦，保证清晰
                float centerX = viewFinder.getWidth() / 2f;
                float centerY = viewFinder.getHeight() / 2f;
                focusOnPoint(centerX, centerY);
            }
        });
        // 10. 彩蛋
        if (tvLabelSize != null) {
            tvLabelSize.setOnClickListener(v -> {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < 500) {
                    clickCount++;
                } else {
                    clickCount = 1;
                }
                lastClickTime = currentTime;
                if (clickCount == 5) {
                    showAboutDialog();
                    clickCount = 0;
                }
            });
        }
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage("拍了个器\nBeta 0.3\nXiaojacksonwww\n1913049764@qq.com\n\n本软件及其所有相关内容版权归XiaoFan所有，受法律保护，未经许可严禁复制、修改或分发。")
                .setPositiveButton("确定", (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    private void showFocusAnimation(float x, float y) {
        focusHandler.removeCallbacksAndMessages(null);
        int width = ivFocusBox.getWidth();
        int height = ivFocusBox.getHeight();
        ivFocusBox.setX(x - width / 2f);
        ivFocusBox.setY(y - height / 2f);
        ivFocusBox.setVisibility(View.VISIBLE);
        ivFocusBox.setAlpha(1f);
        ivFocusBox.setScaleX(1.5f);
        ivFocusBox.setScaleY(1.5f);
        ivFocusBox.animate().scaleX(1f).scaleY(1f).setDuration(300).setListener(null).start();
        focusHandler.postDelayed(() -> {
            ivFocusBox.animate().alpha(0f).setDuration(300).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    ivFocusBox.setVisibility(View.INVISIBLE);
                }
            }).start();
        }, 1000);
    }

    private void focusOnPoint(float x, float y) {
        if (camera == null) return;
        MeteringPointFactory factory = viewFinder.getMeteringPointFactory();
        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
        camera.getCameraControl().startFocusAndMetering(action);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1. 预览配置 (保持 1080P，但强制 4:3 比例以匹配传感器)
                // 强制 4:3 可以利用传感器的全部面积，进光量最大
                ResolutionSelector previewResSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1440, 1920),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(previewResSelector)
                        .build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // 2. 拍照配置
                ResolutionSelector captureResSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(3000, 4000),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(captureResSelector)
                        .setJpegQuality(100)
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF) // 【关键】这里永远设为 OFF，我们手动控制光
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();

                // 3. 绑定相机
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                // 4. 自动设置曝光补偿

                ExposureState exposureState = camera.getCameraInfo().getExposureState();
                if (exposureState.isExposureCompensationSupported()) {
                    Range<Integer> range = exposureState.getExposureCompensationRange();
                    // 尝试降低 1 档曝光
                    int targetIndex = -1;
                    if (range.contains(targetIndex)) {
                        camera.getCameraControl().setExposureCompensationIndex(targetIndex);
                        Log.d(TAG, "已优化曝光补偿，降低噪点");
                    }
                }

                // 5. 重新绑定缩放和方向监听
                setupCameraControls();

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera initialization failed.", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setupCameraControls() {
        if (camera == null) return;

        // 缩放监听
        camera.getCameraInfo().getZoomState().observe(this, zoomState -> {
            if (zoomState != null) {
                tvZoomDisplay.setText(String.format(Locale.getDefault(), "%.1fx", zoomState.getZoomRatio()));
            }
        });

        // 方向监听
        if (orientationEventListener == null) {
            orientationEventListener = new OrientationEventListener(this) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                    int rotation;
                    if (orientation >= 45 && orientation < 135) rotation = Surface.ROTATION_270;
                    else if (orientation >= 135 && orientation < 225) rotation = Surface.ROTATION_180;
                    else if (orientation >= 225 && orientation < 315) rotation = Surface.ROTATION_90;
                    else rotation = Surface.ROTATION_0;

                    if (imageCapture != null) imageCapture.setTargetRotation(rotation);
                }
            };
            orientationEventListener.enable();
        }
    }



    private void takePhoto() {
        if (imageCapture == null || camera == null) return;

        // 1. 定义拍照动作
        Runnable doTakePicture = () -> {
            String inputName = etFilename.getText().toString().trim();
            final String filename = inputName.isEmpty() ? "Photo_" + System.currentTimeMillis() : inputName;

            // 拍照
            imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@NonNull ImageProxy image) {
                    // 拍完立刻关灯
                    if (isFlashEnabled) {
                        camera.getCameraControl().enableTorch(false);
                    }

                    Bitmap bitmap = imageProxyToBitmap(image);
                    int rotationDegrees = image.getImageInfo().getRotationDegrees();
                    image.close(); // 必须关闭

                    Bitmap rotatedBitmap = rotateBitmap(bitmap, rotationDegrees);
                    Bitmap finalBitmap = addTextToBitmap(rotatedBitmap, rotationDegrees);
                    saveBitmapToGallery(finalBitmap, filename);
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    // 出错也要关灯
                    if (isFlashEnabled) {
                        camera.getCameraControl().enableTorch(false);
                    }
                    Log.e(TAG, "Capture failed", exception);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "拍照失败: " + exception.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        };

        // 2. 根据是否开启闪光灯决定流程
        if (isFlashEnabled) {
            // 先开灯(Torch) -> 延时让AE自动曝光适应 -> 拍照 -> 关灯
            camera.getCameraControl().enableTorch(true).addListener(() -> {
                // 灯亮起后，延迟 400ms 再拍照
                // 这个时间是给相机传感器降低 ISO 用的，防止过曝
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Toast.makeText(this, "正在拍摄...", Toast.LENGTH_SHORT).show();
                    doTakePicture.run();
                }, 400);
            }, ContextCompat.getMainExecutor(this));
        } else {
            // 没开闪光灯，直接拍
            Toast.makeText(this, "正在拍摄...", Toast.LENGTH_SHORT).show();
            doTakePicture.run();
        }
    }


    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        BitmapFactory.Options options = new BitmapFactory.Options();
        // 确保使用 ARGB_8888 (每个像素4字节)，色彩最丰富，不进行降低色彩深度的压缩
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        // 禁止抖动，防止噪点
        options.inDither = false;

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private Bitmap rotateBitmap(Bitmap source, int degrees) {
        if (degrees == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private Bitmap addTextToBitmap(Bitmap imageBitmap, int rotationDegrees) {
        Bitmap.Config config = imageBitmap.getConfig();
        if (config == null) config = Bitmap.Config.ARGB_8888;
        Bitmap newBitmap = imageBitmap.copy(config, true);
        Canvas canvas = new Canvas(newBitmap);
        Paint paint = new Paint();
        paint.setColor(tvOverlay.getCurrentTextColor());
        paint.setAntiAlias(true);

        float viewWidth = viewFinder.getWidth();
        float viewHeight = viewFinder.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return newBitmap;

        float centerX = tvOverlay.getX() + tvOverlay.getWidth() / 2f;
        float centerY = tvOverlay.getY() + tvOverlay.getHeight() / 2f;
        float normX = centerX / viewWidth;
        float normY = centerY / viewHeight;
        float imageWidth = newBitmap.getWidth();
        float imageHeight = newBitmap.getHeight();
        float finalX, finalY;
        float textRotationCorrection = 0;

        if (rotationDegrees == 90 || rotationDegrees == 270) {
            finalX = normX * imageWidth;
            finalY = normY * imageHeight;
            textRotationCorrection = 0;
        } else {
            Matrix matrix = new Matrix();
            matrix.postRotate(-90, 0.5f, 0.5f);
            float[] pts = {normX, normY};
            matrix.mapPoints(pts);
            finalX = pts[0] * imageWidth;
            finalY = pts[1] * imageHeight;
            textRotationCorrection = -90;
        }

        float scaleX = imageWidth / viewWidth;
        float scaleY = imageHeight / viewHeight;
        if (rotationDegrees == 0 || rotationDegrees == 180) {
            scaleX = imageWidth / viewHeight;
            scaleY = imageHeight / viewWidth;
        }
        float scale = Math.max(scaleX, scaleY);

        paint.setTextSize(tvOverlay.getTextSize() * scale);
        paint.setShadowLayer(5f * scale, 2f * scale, 2f * scale, Color.BLACK);

        String text = tvOverlay.getText().toString();
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float textWidth = paint.measureText(text);

        canvas.save();
        canvas.translate(finalX, finalY);
        canvas.rotate(tvOverlay.getRotation() + textRotationCorrection);
        float baselineOffset = (fontMetrics.descent - fontMetrics.ascent) / 2 - fontMetrics.descent;
        canvas.drawText(text, -textWidth / 2f, baselineOffset, paint);
        canvas.restore();
        return newBitmap;
    }

    private void saveBitmapToGallery(Bitmap bitmap, String filename) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/拍了个器");
        }
        try {
            android.net.Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
            if (uri != null) {
                OutputStream stream = getContentResolver().openOutputStream(uri);
                if (stream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                    stream.close();
                    runOnUiThread(() -> Toast.makeText(this, "保存成功: " + filename + ".jpg ", Toast.LENGTH_LONG).show());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
            runOnUiThread(() -> Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) startCamera();
    }
}