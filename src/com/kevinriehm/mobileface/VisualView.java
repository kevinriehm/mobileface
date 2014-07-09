package com.kevinriehm.mobileface;

import java.nio.ByteBuffer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import org.opencv.core.Mat;

public class VisualView extends SurfaceView implements SurfaceHolder.Callback {
	private static final String TAG = "MobileFace-VisualView";

	public final int MODE_CAMERA = 0;

	private int mode;

	private boolean enabled;
	private boolean shouldEnable;

	public String classifierPath;
	public String modelPath;
	public String paramsPath;

	private Bitmap bitmap;
	private Matrix matrix;

	private ByteBuffer data;

	private int fps;
	private int fpsCount;
	private long fpsStart;

	static {
		System.loadLibrary("gnustl_shared");
		System.loadLibrary("opencv_java");
		System.loadLibrary("mobileface");
	}

	public VisualView(Context context, AttributeSet attrs) {
		super(context,attrs);

		enabled = false;

		// Default to the camera
		setMode(MODE_CAMERA);

		// Prepare the FPS counter
		fps = 0;
		fpsCount = 0;
		fpsStart = System.currentTimeMillis();

		// Get notifications about the surface
		getHolder().addCallback(this);

		// Handle drawing ourselves
//		setWillNotDraw(false);
	}

	// Getters and setters

	public void setMode(int _mode) {
		mode = _mode;

		disable();
		enable();
	}

	public void setClassifierPath(String path) {
		classifierPath = path;
	}

	public void setModelPath(String path) {
		modelPath = path;
	}

	public void setParamsPath(String path) {
		paramsPath = path;
	}

	// Other public stuff

	public void enable() {
		Log.i(TAG,"enable()");

		// No reason to repeat ourselves ourselves
		if(enabled) return;

		// Still be paranoid, though
		disable();

		if(bitmap == null) {
			Log.e(TAG,"enable() called before surface creation");
			shouldEnable = true;
			return;
		}

		// Now do the actual setup
		spawnWorker(mode,bitmap);

		enabled = true;
		shouldEnable = false;
	}

	public void disable() {
		Log.i(TAG,"disable()");

		terminateWorker();

		enabled = false;
	}

	public native void resetTracking();
	public native boolean saveFaceImage(String filename);

	// SurfaceView.Callback implementation

	public void surfaceCreated(SurfaceHolder holder) {
		Log.i(TAG,"surfaceCreated()");
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		Log.i(TAG,"surfaceChanged()");

		boolean reenable = enabled || shouldEnable;

		if(reenable) disable();

		if(bitmap != null) bitmap.recycle();
		bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);

		if(reenable) enable();
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.i(TAG,"surfaceDestroyed()");

		disable();
	}

	// JNI declarations

	private native void spawnWorker(int mode, Bitmap bitmap);
	private native void terminateWorker();

	// Etc.

	private void blitBitmap() {
		if(bitmap == null) return;

		Canvas canvas = getHolder().lockCanvas();
		canvas.drawBitmap(bitmap,0,0,null);
		getHolder().unlockCanvasAndPost(canvas);
	}

	// Accumulate camera and device orientation
	private int getSummedOrientation() {
		int orientation;
		int adjustment;

		orientation = 0;

		WindowManager windowManager = (WindowManager) getContext().getSystemService("window");
		switch(windowManager.getDefaultDisplay().getRotation()) {
		default:
		case Surface.ROTATION_0:   adjustment =   0; break;
		case Surface.ROTATION_90:  adjustment =  90; break;
		case Surface.ROTATION_180: adjustment = 180; break;
		case Surface.ROTATION_270: adjustment = 270; break;
		}

		return (orientation + adjustment)%360;
	}
}

