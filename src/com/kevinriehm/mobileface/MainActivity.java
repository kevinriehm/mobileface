package com.kevinriehm.mobileface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.objdetect.CascadeClassifier;

public class MainActivity extends Activity implements CvCameraViewListener2 {
	private CameraBridgeViewBase cameraView;

	private String faceFilePath;
	private CascadeClassifier faceDetector;

	private BaseLoaderCallback opencvLoaderCallback = new BaseLoaderCallback(this) {
		public void onManagerConnected(int status) {
			if(status == LoaderCallbackInterface.SUCCESS) {
				// Set up the face detector
				faceDetector = new CascadeClassifier(faceFilePath);

				// Activate the camera processing
				cameraView.enableView();
			} else super.onManagerConnected(status);
		}
	};

	// Activity overrides

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.main);

		// Copy the cascade specification to an actual file
		try {
			InputStream faceRes = getResources().openRawResource(R.raw.lbpcascade_frontalface);

			File faceFile = new File(getDir("cascades",Context.MODE_PRIVATE),"lbpcascade_frontalface.xml");
			FileOutputStream faceFileStream = new FileOutputStream(faceFile);
			faceFilePath = faceFile.getAbsolutePath();

			try {
				int numBytes;
				byte[] buf = new byte[1024];
				while((numBytes = faceRes.read(buf)) > 0)
					faceFileStream.write(buf,0,numBytes);
			} finally {
				faceRes.close();
				faceFileStream.close();
			}
		} catch(Exception e) {
		}

		// Set up the camera view
		cameraView = (CameraBridgeViewBase) findViewById(R.id.camera_view);
		cameraView.setCvCameraViewListener(this);
		cameraView.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_FRONT);
	//	cameraView.setMaxFrameSize(200,200);
		cameraView.enableFpsMeter();
	}

	public void onPause() {
		super.onPause();

		cameraView.disableView();
	}

	public void onResume() {
		super.onResume();

		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_9,this,opencvLoaderCallback);
	}

	public void onDestroy() {
		super.onDestroy();

		cameraView.disableView();
	}

	// CvCameraViewListener2 implementation

	public void onCameraViewStarted(int width, int height) {
	}

	public void onCameraViewStopped() {
	}

	// Draw an ellipse around any detected faces
	public Mat onCameraFrame(CvCameraViewFrame frame) {
		Mat gray = frame.gray();
		Mat rgba = frame.rgba();

		MatOfRect faces = new MatOfRect();

		double minFaceSize = Math.round(0.5*gray.rows());

		faceDetector.detectMultiScale(gray,faces,1.1,2,2,new Size(minFaceSize,minFaceSize),new Size());

		for(Rect rect : faces.toArray())
			Core.ellipse(rgba,new RotatedRect(new Point((rect.tl().x + rect.br().x)/2,(rect.tl().y + rect.br().y)/2),rect.size(),0),
				new Scalar(255,0,0));

		return rgba;
	}
}
