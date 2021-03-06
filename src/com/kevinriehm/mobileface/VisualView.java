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

	public static final int MODE_CAMERA = 0;
	public static final int MODE_FILE   = 1;

	private int mode;

	private boolean enabled;
	private boolean shouldEnable;

	private String modelPath;
	private String paramsPath;
	private String avatarPath;

	private String videoPath;

	private Bitmap bitmap;
	private Matrix matrix;

	private ByteBuffer data;

	private OrientationEventListener orientListener;

	static {
		System.loadLibrary("gnustl_shared");
		System.loadLibrary("opencv_java");
		System.loadLibrary("avutil");
		System.loadLibrary("avcodec");
		System.loadLibrary("avformat");
		System.loadLibrary("swscale");
		System.loadLibrary("mobileface");
	}

	public VisualView(Context context, AttributeSet attrs) {
		super(context,attrs);

		enabled = false;
		shouldEnable = false;

		// Default to the camera
		setMode(MODE_CAMERA);

		// Get notifications about the surface
		getHolder().addCallback(this);

		// Get notifications about orientation changes
		orientListener = new OrientationEventListener(context) {
			private int oldOrientation = getDeviceOrientation()/90;

			public void onOrientationChanged(int orientation) {
				orientation = (orientation + 45)/90%4;

				// We only need to know about 180 degree turns
				if(oldOrientation != (orientation + 2)%4 || oldOrientation == orientation)
					return;

				setViewOrientation(90*orientation);

				oldOrientation = orientation;
			}
		};
		orientListener.enable();
	}

	// Getters and setters

	public void setMode(int _mode) {
		mode = _mode;
	}

	public void setModelPath(String path) {
		modelPath = path;
	}

	public void setParamsPath(String path) {
		paramsPath = path;
	}

	public void setAvatarPath(String path) {
		avatarPath = path;
	}

	public void setVideoPath(String path) {
		videoPath = path;
	}

	public native void setViewOrientation(int orientation);

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
		setViewOrientation(getDeviceOrientation());

		enabled = true;
		shouldEnable = false;
	}

	public void disable() {
		Log.i(TAG,"disable()");

		if(!enabled) return;

		terminateWorker();

		enabled = false;
	}

	public native void resetTracking();
	public native boolean calibrateExpression();
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

	private void blitBitmap(int x, int y, int w, int h) {
		if(bitmap == null) return;

		Rect dirty = new Rect(x,y,x + w,y + h);

		Canvas canvas = getHolder().lockCanvas();
		canvas.drawBitmap(bitmap,dirty,dirty,null);
		getHolder().unlockCanvasAndPost(canvas);
	}

	// Accumulate camera and device orientation
	private int getDeviceOrientation() {
		WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);

		switch(windowManager.getDefaultDisplay().getRotation()) {
		default:
		case Surface.ROTATION_0:   return   0;
		case Surface.ROTATION_90:  return  90;
		case Surface.ROTATION_180: return 180;
		case Surface.ROTATION_270: return 270;
		}
	}
}

