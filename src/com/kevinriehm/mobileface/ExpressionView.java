package com.kevinriehm.mobileface;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

public class ExpressionView extends GLSurfaceView implements GLSurfaceView.Renderer {
	private static final String TAG = "MobileFace-ExpressionView";

	public ExpressionView(Context context, AttributeSet attrs) {
		super(context,attrs);

		setEGLContextClientVersion(2);

		setRenderer(this);
	}

	// GLSurfaceView.Renderer implementation

	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
	}

	public void onSurfaceChanged(GL10 gl, int width, int height) {
		GLES20.glViewport(0,0,width,height);
	}

	public void onDrawFrame(GL10 gl) {
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
	}
}

