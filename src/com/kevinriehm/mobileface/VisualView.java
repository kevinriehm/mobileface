package com.kevinriehm.mobileface;

import android.content.Context;
import android.graphics.ImageFormat;
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

public class VisualView extends ViewGroup implements Camera.PreviewCallback, SurfaceHolder.Callback {
	private static final String TAG = "MobileFace-VisualView";

	private FrameCallback callback;

	private Mode mode;

	private boolean enabled;

	private Camera camera;
	private Camera.CameraInfo cameraInfo;
	private int cameraFacing;

	private SurfaceView surface;

	private int sourceWidth;
	private int sourceHeight;

	private TextView statusText;

	private int fps;
	private int fpsCount;
	private long fpsStart;

	public VisualView(Context context, AttributeSet attrs) {
		super(context,attrs);

		enabled = false;

		// Create the actual display surface
		surface = new SurfaceView(context);
		addView(surface);

		// Get notifications about the surface
		surface.getHolder().addCallback(this);

		// Default to the front-facing camera
		mode = Mode.CAMERA;
		cameraFacing = Camera.CameraInfo.CAMERA_FACING_FRONT;

		// Just fill in some arbitrary values so we don't crash
		sourceWidth = 800;
		sourceHeight = 600;

		// Add an info box
		statusText = new TextView(context);
		statusText.setGravity(Gravity.TOP | Gravity.CENTER);
		addView(statusText);

		// FPS counter
		fps = 0;
		fpsCount = 0;
		fpsStart = System.currentTimeMillis();
	}

	// Getters and setters

	public void setMode(Mode _mode) {
		mode = _mode;

		disable();
		enable();
	}

	public void setCameraFacing(int _cameraFacing) {
		cameraFacing = _cameraFacing;

		disable();
		enable();
	}

	// Other public stuff

	public enum Mode {
		CAMERA
	}

	public interface FrameCallback {
		public Mat onFrame(Mat frame);
	}

	public void setFrameCallback(FrameCallback _callback) {
		callback = _callback;
	}

	public void enable() {
		Log.i(TAG,"enable()");

		// No reason to repeat ourselves ourselves
		if(enabled)
			return;

		// Still be paranoid, though
		disable();

		// Now do the actual setup
		switch(mode) {
		case CAMERA:
			findCamera();
			activateCamera();
			updateOrientation();
			camera.startPreview();
			break;
		}

		requestLayout();

		enabled = true;
	}

	public void disable() {
		Log.i(TAG,"disable()");

		switch(mode) {
		case CAMERA:
			if(camera != null) {
				camera.stopPreview();
				camera.setPreviewCallbackWithBuffer(null);
				releaseCamera();
			}
			break;
		}

		enabled = false;
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

		statusText.layout(left,top,right,bottom);
	}

	// Camera.PreviewCallback implementation

	public void onPreviewFrame(byte[] data, Camera camera) {
		// Point ourselves the right way around
		updateOrientation();

		// FPS counter
		fpsCount++;
		long time = System.currentTimeMillis();
		if(time >= fpsStart + 1000) {
			fps = fpsCount;
			fpsCount = 0;
			fpsStart += 1000;
		}

		int targetFps[] = new int[2];
		camera.getParameters().getPreviewFpsRange(targetFps);

		// Update the info box
		statusText.setText(""
			+ sourceWidth + "x" + sourceHeight + "\n"
			+ fps + " FPS (Target: " + (targetFps[0]/1000.) + "-" + (targetFps[1]/1000.) + ")"
		);

		// Put the buffer back in the bucket
		camera.addCallbackBuffer(data);
	}

	// SurfaceHolder.Callback implementation

	public void surfaceCreated(SurfaceHolder holder) {
		Log.i(TAG,"surfaceCreated()");
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		Log.i(TAG,"surfaceChanged()");

		disable();
		enable();
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.i(TAG,"surfaceDestroyed()");

		disable();
	}

	// Etc.

	// Accumulate camera and device orientation
	private int getSummedOrientation() {
		int orientation;
		int adjustment;

		orientation = cameraInfo == null ? 0 : cameraInfo.orientation;

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

	// Adjust the camera preview based on the device orientation
	private void updateOrientation() {
		camera.setDisplayOrientation((360 - getSummedOrientation())%360);
	}

	// Find the first camera matching our parameters, or the last one
	private void findCamera() {
		Log.i(TAG,"findCamera()");

		int numCameras = Camera.getNumberOfCameras();
		for(int i = 0; i < numCameras; i++) {
			cameraInfo = new Camera.CameraInfo();
			Camera.getCameraInfo(i,cameraInfo);

			try {
				if(cameraInfo.facing == cameraFacing || i == numCameras - 1) {
					camera = Camera.open(i);
					Log.i(TAG,"Opened camera " + i + " (" + camera + ")");
					return;
				}
			} catch(Exception e) {
			}
		}

		camera = null;
		cameraInfo = null;
	}

	// Activate the camera preview
	private void activateCamera() {
		Log.i(TAG,"activateCamera()");
		Log.i(TAG,"trying to activate camera (" + camera + ")");

		if(camera != null) {
			try {
				Log.i(TAG,"activating camera (" + camera + ")");

				camera.setPreviewCallbackWithBuffer(this);
				camera.setPreviewDisplay(surface.getHolder());

				Camera.Parameters params = camera.getParameters();

				// Use the largest available preview size
				sourceWidth = 0;
				sourceHeight = 0;

				for(Camera.Size size : params.getSupportedPreviewSizes()) {
					if(size.width*size.height > sourceWidth*sourceHeight) {
						sourceWidth = size.width;
						sourceHeight = size.height;
					}
				}

				params.setPreviewSize(sourceWidth,sourceHeight);

				for(Integer i : params.getSupportedPreviewFormats()) {
					Log.i(TAG,"Supported format: " + i);
				}

				camera.setParameters(params);

				// Allocate a frame buffer
				int bufferSize = sourceWidth*sourceHeight
					*ImageFormat.getBitsPerPixel(ImageFormat.NV21);
				camera.addCallbackBuffer(new byte[bufferSize]);
			} catch(Exception e) {
				Log.e(TAG,e.toString());
				e.printStackTrace();
			}
		}
	}

	// Clean up the camera
	private void releaseCamera() {
		Log.i(TAG,"releaseCamera()");

		if(camera != null)
			camera.release();

		camera = null;
		cameraInfo = null;
	}
}

