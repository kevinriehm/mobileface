package com.kevinriehm.mobileface;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Random;
import java.util.Scanner;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

public class ExpressionView extends GLSurfaceView implements GLSurfaceView.Renderer {
	private static final String TAG = "MobileFace-ExpressionView";

	private static final int numExpressionPoints = 66;

	private String avatarPath;
	private String expressionPath;

	private boolean avatarReady;
	private boolean framesReady;
	private boolean dataReady;

	private long startTime;

	private double fps;
	private Frame[] frames;

	private int faceVertexShader;
	private int faceFragmentShader;
	private int faceProgram;

	private float maxCoord;

	private int numTris;
	private ShortBuffer triBuffer;

	private int avatarWidth, avatarHeight;
	private Bitmap avatarBitmap;
	private int avatarTexture;
	private FloatBuffer avatarUVs;

	private float[] camera;

	// Shader input locations
	private int uCamera;
	private int uTexture;

	private int aPosition;
	private int aUV;

	static {
		System.loadLibrary("mobileface");
	}

	public ExpressionView(Context context, AttributeSet attrs) {
		super(context,attrs);

		avatarReady = false;
		framesReady = false;
		dataReady = false;
		expressionPath = null;

		startTime = 0;

		maxCoord = 1;

		setEGLContextClientVersion(2);

		setRenderer(this);
	}

	// Public stuff

	public void setAvatarFilePath(String path) {
		avatarReady = false;
		dataReady = false;

		avatarPath = path;
	}

	public void setExpressionFilePath(String path) {
		framesReady = false;
		dataReady = false;

		expressionPath = path;
	}

	// GLSurfaceView.Renderer implementation

	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		dataReady = false;
	}

	public void onSurfaceChanged(GL10 gl, int width, int height) {
		GLES20.glViewport(0,0,width,height);

		updateCamera();
	}

	public void onDrawFrame(GL10 gl) {
		// Make sure we can render
		if(!avatarReady) readAvatarFile();
		if(!avatarReady) return;

		if(!framesReady) readExpressionFile();
		if(!framesReady) return;

		if(!dataReady) setupGLData();
		if(!dataReady) return;

		// Which frame are we on?
		long now = System.currentTimeMillis();
		if(startTime == 0)
			startTime = now;

		int currentFrame = (int) ((now - startTime)*fps/1000);
		currentFrame = Math.max(0,Math.min(frames.length - 1,currentFrame));

		Frame frame = frames[currentFrame];

		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

		if(frame != null && frame.pointsBuffer != null) {
			// Set up the texture
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,avatarTexture);

			// Set up the shader
			GLES20.glUseProgram(faceProgram);

			// Set up the shader inputs
			GLES20.glUniformMatrix4fv(uCamera,1,false,camera,0);

			GLES20.glUniform1i(uTexture,0);

			GLES20.glEnableVertexAttribArray(aPosition);
			GLES20.glVertexAttribPointer(aPosition,3,GLES20.GL_FLOAT,false,0,frame.pointsBuffer);

			GLES20.glEnableVertexAttribArray(aUV);
			GLES20.glVertexAttribPointer(aUV,2,GLES20.GL_FLOAT,false,0,avatarUVs);

			// Render!
			GLES20.glDrawElements(GLES20.GL_TRIANGLES,3*numTris,GLES20.GL_UNSIGNED_SHORT,triBuffer);
		}
	}

	// Helper classes and functions

	private class Frame {
		boolean hasFace;

		int numPoints;
		FloatBuffer pointsBuffer;
	}

	private void readAvatarFile() {
		if(avatarPath == null)
			return;

		int[] size = new int[2];
		if(!getAvatarSize(avatarPath,size))
			return;

		avatarWidth = size[0];
		avatarHeight = size[1];

		avatarBitmap = Bitmap.createBitmap(avatarWidth,avatarHeight,Bitmap.Config.ARGB_8888);
		avatarUVs = ByteBuffer.allocateDirect(2*4*numExpressionPoints)
			.order(ByteOrder.nativeOrder())
			.asFloatBuffer();

		if(!getAvatarImage(avatarPath,avatarBitmap,avatarUVs))
			return;

		avatarReady = true;
	}

	private native boolean getAvatarSize(String path, int[] size);
	private native boolean getAvatarImage(String path, Bitmap bitmap, FloatBuffer uvs);

	public void readExpressionFile() {
		if(expressionPath == null)
			return;

		maxCoord = 1;

		try {
			// Read in and parse the entire file
			String fileString = "";
			FileInputStream fileStream = new FileInputStream(expressionPath);

			int numBytes;
			byte[] buf = new byte[100000];
			while((numBytes = fileStream.read(buf)) >= 0)
				fileString += new String(buf,0,numBytes);

			JSONObject jsonExpression = new JSONObject(fileString);

			// Get general info about the animation
			fps = jsonExpression.getDouble("fps");

			JSONArray jsonFrames = jsonExpression.getJSONArray("frames");

			int numFrames = jsonFrames.length();

			// Abort if there aren't any frames
			if(numFrames == 0) return;

			// Import each frame
			Log.i(TAG,"reading " + numFrames + " frames from '" + expressionPath + "'");
			frames = new Frame[numFrames];
			for(int i = 0; i < numFrames; i++) {
				Frame frame = new Frame();
				JSONObject jsonFrame = jsonFrames.getJSONObject(i);

				// Import the points of the face
				if(frame.hasFace = jsonFrame.getBoolean("has_face")) {
					JSONArray points = jsonFrame.getJSONArray("points3d");

					frame.numPoints = points.length();
					frame.pointsBuffer = ByteBuffer.allocateDirect(3*4*points.length())
						.order(ByteOrder.nativeOrder())
						.asFloatBuffer();

					for(int j = 0; j < frame.numPoints; j++) {
						JSONArray point = points.getJSONArray(j);

						float x = (float) point.getDouble(0);
						float y = (float) point.getDouble(1);
						float z = (float) point.getDouble(2);

						maxCoord = Math.max(maxCoord,Math.max(x,Math.max(y,z)));

						frame.pointsBuffer.put(x);
						frame.pointsBuffer.put(y);
						frame.pointsBuffer.put(z);
					}

					frame.pointsBuffer.rewind();
				}

				frames[i] = frame;
			}

			// Also read in the triangle list
			InputStream triStream = getContext().getResources().openRawResource(R.raw.face_tri);
			Scanner triScanner = new Scanner(triStream);

			triScanner.skip("n_tri:");
			numTris = triScanner.nextInt();
			triScanner.skip("\\s*\\{\\s*");

			triBuffer = ByteBuffer.allocateDirect(3*2*numTris)
				.order(ByteOrder.nativeOrder())
				.asShortBuffer();

			for(int i = 0; i < numTris; i++) {
				triBuffer.put(triScanner.nextShort());
				triBuffer.put(triScanner.nextShort());
				triBuffer.put(triScanner.nextShort());
			}

			triBuffer.rewind();

			framesReady = true;
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}

		updateCamera();

		((Activity) getContext()).runOnUiThread(new Runnable() {
			public void run() {
				ViewGroup parent = (ViewGroup) getParent();
				View throbber = parent.findViewById(R.id.expression_throbber);
				if(throbber != null) parent.removeView(throbber);
			}
		});
	}

	private void updateCamera() {
		// Projection matrix
		camera = new float[16];

		int width = getWidth();
		int height = getHeight();

		float scalew, scaleh;
		if(width > height) {
			scalew = (float) width/height;
			scaleh = 1;
		} else {
			scalew = 1;
			scaleh = (float) height/width;
		}

		Matrix.setIdentityM(camera,0);
		Matrix.scaleM(camera,0,1f/maxCoord,1f/maxCoord,1f/maxCoord);
		Matrix.scaleM(camera,0,1f/scalew,1f/scaleh,1);
		Matrix.scaleM(camera,0,1,-1,1);
	}

	private int compileShaderFromResource(int resid, int type) {
		String source = "";

		// Get the shader's source code
		try {
			InputStream input = getContext().getResources().openRawResource(resid);

			int numBytes;
			byte[] buf = new byte[1024];
			while((numBytes = input.read(buf)) > 0)
				source += new String(buf,0,numBytes);
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}

		// Generate and compile the shader
		int shader = GLES20.glCreateShader(type);
		GLES20.glShaderSource(shader,source);
		GLES20.glCompileShader(shader);

		// Report on anything interesting
		String log = GLES20.glGetShaderInfoLog(shader);
		if(log.length() > 3)
			Log.e(TAG,"Shader compile log:\n" + log);

		return shader;
	}

	// Do everything necessary to be ready for rendering
	private void setupGLData() {
		if(!avatarReady || !framesReady || dataReady)
			return;

		// Shaders
		faceVertexShader = compileShaderFromResource(R.raw.face_vertex,GLES20.GL_VERTEX_SHADER);
		faceFragmentShader = compileShaderFromResource(R.raw.face_fragment,GLES20.GL_FRAGMENT_SHADER);

		// Shading program
		faceProgram = GLES20.glCreateProgram();
		GLES20.glAttachShader(faceProgram,faceVertexShader);
		GLES20.glAttachShader(faceProgram,faceFragmentShader);
		GLES20.glLinkProgram(faceProgram);

		// Input locations
		uCamera = GLES20.glGetUniformLocation(faceProgram,"u_camera");
		uTexture = GLES20.glGetUniformLocation(faceProgram,"u_texture");

		aPosition = GLES20.glGetAttribLocation(faceProgram,"a_position");
		aUV = GLES20.glGetAttribLocation(faceProgram,"a_uv");

		// Textures
		int[] texs = new int[1];
		GLES20.glGenTextures(1,texs,0);
		avatarTexture = texs[0];

		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,avatarTexture);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
		GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,avatarBitmap,0);

		dataReady = true;
	}
}

