package com.kevinriehm.mobileface;

import java.nio.ByteBuffer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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

public class VisualView extends ViewGroup {
	private static final String TAG = "MobileFace-VisualView";

	private Mode mode;

	private boolean enabled;

	private VisualSurfaceView surface;

	private int sourceWidth;
	private int sourceHeight;

	static {
		System.loadLibrary("gnustl_shared");
		System.loadLibrary("opencv_java");
		System.loadLibrary("mobileface");
	}

	public VisualView(Context context, AttributeSet attrs) {
		super(context,attrs);

		enabled = false;

		// Create the actual display surface
		surface = new VisualSurfaceView(context);
		addView(surface);

		// Default to the camera
		mode = Mode.CAMERA;

		// Just fill in some arbitrary values so we don't crash
		sourceWidth = 800;
		sourceHeight = 600;
	}

	// Getters and setters

	public void setMode(Mode _mode) {
		mode = _mode;

		disable();
		enable();
	}

	public void setClassifierPath(String path) {
		surface.classifierPath = path;
	}

	// Other public stuff

	public enum Mode {
		CAMERA
	}

	public void enable() {
		Log.i(TAG,"enable()");

		// No reason to repeat ourselves ourselves
		if(enabled)
			return;

		// Still be paranoid, though
		disable();

		// Now do the actual setup
		requestLayout();

		enabled = true;
	}

	public void disable() {
		Log.i(TAG,"disable()");

		switch(mode) {
		case CAMERA:
			surface.disable();
			break;
		}

		enabled = false;
	}

	public boolean saveFaceImage(String filename) {
		return surface.saveFaceImage(filename);
	}

	// ViewGroup overrides

	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		int width = right - left;
		int height = bottom - top;

		int visualWidth;
		int visualHeight;

		// Adjust for orientation
		int orientation = getSummedOrientation();
		if(orientation == 0 || orientation == 180) {
			visualWidth = sourceWidth;
			visualHeight = sourceHeight;
		} else {
			visualWidth = sourceHeight;
			visualHeight = sourceWidth;
		}

		int hortMargin;
		int vertMargin;

		// Fit to the display area
		if(sourceWidth*height > width*sourceHeight) { // Source is too wide
			hortMargin = 0;
			vertMargin = (height - width*visualHeight/visualWidth)/2;
		} else { // Source is too skinny
			hortMargin = (width - height*visualWidth/visualHeight)/2;
			vertMargin = 0;
		}

		surface.layout(left + hortMargin,top + vertMargin,right - hortMargin,bottom - vertMargin);
	}

	// Etc.

	private class VisualSurfaceView extends SurfaceView {
		public String classifierPath;

		private Bitmap bitmap;
		private Matrix matrix;

		private ByteBuffer data;

		private int fps;
		private int fpsCount;
		private long fpsStart;

		VisualSurfaceView(Context context) {
			super(context);

			fps = 0;
			fpsCount = 0;
			fpsStart = System.currentTimeMillis();

			setWillNotDraw(false);
		}

		private native boolean saveFaceImage(String filename);

		private native void processCameraFrame(Bitmap bitmap);
		private native void releaseCamera();

		public void disable() {
			switch(mode) {
			case CAMERA: releaseCamera(); break;
			}
		}

		private void setSourceDimensions(int width, int height) {
			sourceWidth = width;
			sourceHeight = height;
		}

		protected void onSizeChanged(int w, int h, int oldw, int oldh) {
			if(w == oldw && h == oldh) return;

			if(bitmap != null) bitmap.recycle();
			bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
		}

		protected void onDraw(Canvas canvas) {
			if(!enabled) return;

			switch(mode) {
			case CAMERA: processCameraFrame(bitmap); break;
			}

			matrix = new Matrix();
			matrix.postScale((float) getWidth()/sourceWidth,(float) getHeight()/sourceHeight);

			canvas.drawBitmap(bitmap,matrix,null);

			// Handle FPS
			fpsCount++;
			if(System.currentTimeMillis() >= fpsStart + 1000) {
				fps = fpsCount;
				fpsCount = 0;
				fpsStart += 1000;
			}

			// Draw the next frame as soon as possible
			invalidate();
		}
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

