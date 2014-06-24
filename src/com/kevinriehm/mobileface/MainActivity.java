package com.kevinriehm.mobileface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Browser;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieSyncManager;
import android.webkit.CookieManager;
import android.widget.Toast;

import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.auth.AccessToken;
import twitter4j.auth.RequestToken;
import twitter4j.conf.ConfigurationBuilder;

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
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

public class MainActivity extends Activity implements CvCameraViewListener2 {
	private static final String TAG = "MobileFace-MainActivity";

	private static final int REQUEST_TWITTER_AUTH = 0;

	private static final String TWITTER_KEY = "DyrwKl9MGt0MmjRAVlKbqL7XB";
	private static final String TWITTER_SECRET = "w6MiNQ3ONhFPK0JJiKD9IkWADfmKjXyhEde2vxk2SHrYX4k8wz";

	private static final double FACE_SCALE = 0.3;

	private CameraBridgeViewBase cameraView;

	private String faceFilePath;
	private CascadeClassifier faceDetector;

	private Mat currentFrameGray;
	private Mat currentFrameRgba;
	private MatOfRect currentFrameFaces;

	private Twitter twitter;
	private RequestToken requestToken;

	private String twitterToken = null;
	private String twitterSecret = null;

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
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}

		// Set up the camera view
		cameraView = (CameraBridgeViewBase) findViewById(R.id.camera_view);
		cameraView.setCvCameraViewListener(this);
		cameraView.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_FRONT);
//		cameraView.setMaxFrameSize(200,200);
		cameraView.enableFpsMeter();
	}

	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main,menu);
		return true;
	}

	public void onPause() {
		super.onPause();

		cameraView.disableView();
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		Log.i(TAG,"activity result: " + (data == null ? data : data.toString()));

		switch(requestCode) {
			case REQUEST_TWITTER_AUTH:
				if(resultCode == RESULT_OK) {
					receiveTwitterCredentials(data.getData());
				}
				break;

			default: Log.e(TAG,"unhandled requestCode: " + requestCode); break;
		}
	}

	public void onResume() {
		super.onResume();

		// Make sure we have access to Twitter
		checkTwitterCredentials();

		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_9,this,new BaseLoaderCallback(this) {
			public void onManagerConnected(int status) {
				if(status == LoaderCallbackInterface.SUCCESS) {
					// Set up the face detector
					faceDetector = new CascadeClassifier(faceFilePath);

					// Activate the camera processing
					cameraView.enableView();
				} else super.onManagerConnected(status);
			}
		});
	}

	// Activity overrides

	public void onDestroy() {
		super.onDestroy();

		cameraView.disableView();
	}

	// CvCameraViewListener2 implementation

	public void onCameraViewStarted(int width, int height) {
	}

	public void onCameraViewStopped() {
		currentFrameGray = null;
		currentFrameRgba = null;
		currentFrameFaces = null;
	}

	public Mat onCameraFrame(CvCameraViewFrame frame) {
		Mat gray = frame.gray();
		Mat rgba = frame.rgba();

		// Draw an ellipse around any detected faces
		MatOfRect faces = findFaces(gray);

		// Save this in case the user takes a picture now
		if(currentFrameGray != null) currentFrameGray.release();
		if(currentFrameRgba != null) currentFrameRgba.release();

		currentFrameGray = gray.clone();
		currentFrameRgba = rgba.clone();
		currentFrameFaces = faces;

		for(Rect rect : faces.toArray()) {
			Point midpoint = new Point((rect.tl().x + rect.br().x)/2, (rect.tl().y + rect.br().y)/2);
			Core.ellipse(rgba,new RotatedRect(midpoint,rect.size(),0),new Scalar(255,0,0));
		}

		return rgba;
	}

	// UI callbacks

	public void tweetSelfie(View view) {
		// Sanity checks
		if(currentFrameGray == null || currentFrameRgba == null)
			return;

		if(currentFrameFaces.toArray().length < 1) { // Didn't find any faces?
			AlertDialog.Builder builder = new AlertDialog.Builder(this);

			builder.setTitle(R.string.no_faces_title);
			builder.setMessage(R.string.no_faces_message);
			builder.setPositiveButton(R.string.no_faces_button,new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
				}
			});

			builder.create().show();

			return;
		}

		// Crop it out and save it to a file
		Mat faceMatBGR = null;
		Mat faceMatRGB = null;
		try {
			Log.i(TAG,currentFrameFaces.toArray()[0].toString());
			faceMatBGR = new Mat();
			faceMatRGB = new Mat(currentFrameRgba,currentFrameFaces.toArray()[0]);
			Imgproc.cvtColor(faceMatRGB,faceMatBGR,Imgproc.COLOR_RGB2BGR);
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}

		File faceFile = null;
		try {
			// Get our location
			Criteria criteria = new Criteria();
			criteria.setAccuracy(Criteria.ACCURACY_FINE);

			LocationManager locationmanager = (LocationManager) getSystemService(LOCATION_SERVICE);
			String provider = locationmanager.getBestProvider(criteria,true);
			Location location = provider == null ? null : locationmanager.getLastKnownLocation(provider);

			// Record the date and time
			SimpleDateFormat format = new SimpleDateFormat("cccc MMMM d, y 'at' h:mm a");

			// Build the tweet text
			String tweet = "My face on " + format.format(new Date()) + ".";

			// Save the tweet image
			faceFile = File.createTempFile("selfie",".jpg",getDir("temp",Context.MODE_PRIVATE));

			Highgui.imwrite(faceFile.getAbsolutePath(),faceMatBGR);

			// Send it on its merry way
			new TweetFaceDialog(this,twitter,tweet,faceFile,location).show();
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}

		if(faceMatBGR != null) faceMatBGR.release();
		if(faceMatRGB != null) faceMatRGB.release();
	}

	public void deauthenticate(MenuItem item) {
		// Wipe it all out
		twitterToken = null;
		twitterSecret = null;

		SharedPreferences.Editor prefsEditor = getSharedPreferences("",MODE_PRIVATE).edit();
		prefsEditor.remove(getResources().getString(R.string.pref_twitter_token));
		prefsEditor.remove(getResources().getString(R.string.pref_twitter_secret));
		prefsEditor.apply();

		CookieSyncManager.createInstance(this);
		CookieManager.getInstance().removeAllCookie();

		// Notify the user
		Toast.makeText(this,R.string.deauthed_message,Toast.LENGTH_SHORT);

		// Get new credentials
		checkTwitterCredentials();
	}

	// Helper classes/functions

	private class GetTwitterCredentialsTask extends AsyncTask<Void, Void, Void> {
		ProgressDialog dialog;

		protected void onPreExecute() {
			Log.i(TAG,"getting Twitter credentials");

			dialog = ProgressDialog.show(MainActivity.this,"Please Wait","",true,false);
		}

		protected Void doInBackground(Void... args) {
			try {
				// Actual authentication stuff
				requestToken = twitter.getOAuthRequestToken(getResources().getString(R.string.twitter_callback_url));

				Intent intent = new Intent(MainActivity.this,TwitterAuthActivity.class);
				intent.setData(Uri.parse(requestToken.getAuthenticationURL()));
				startActivityForResult(intent,REQUEST_TWITTER_AUTH);
			} catch(Exception e) {
				Log.e(TAG,e.toString());
				e.printStackTrace();
			}

			return null;
		}

		protected void onPostExecute(Void result) {
			dialog.dismiss();
		}
	}

	private class VerifyTwitterCredentialsTask extends AsyncTask<Uri, Void, Void> {
		ProgressDialog dialog;

		protected void onPreExecute() {
			Log.i(TAG,"verifying Twitter credentials");

			dialog = ProgressDialog.show(MainActivity.this,"Please Wait","",true,false);
		}

		protected Void doInBackground(Uri... args) {
			try {
				String verifier = args[0].getQueryParameter("oauth_verifier");
				Log.i(TAG,"OAuth verifier: " + verifier);
				AccessToken accessToken = twitter.getOAuthAccessToken(requestToken,verifier);

				twitterToken = accessToken.getToken();
				twitterSecret = accessToken.getTokenSecret();

				SharedPreferences.Editor prefsEditor = getSharedPreferences("",MODE_PRIVATE).edit();
				prefsEditor.putString(getResources().getString(R.string.pref_twitter_token),twitterToken);
				prefsEditor.putString(getResources().getString(R.string.pref_twitter_secret),twitterSecret);
				prefsEditor.apply();
			} catch(Exception e) {
				Log.e(TAG,e.toString());
				e.printStackTrace();
			}

			return null;
		}

		protected void onPostExecute(Void result) {
			dialog.dismiss();
		}
	}

	// Ensure we are authorized with Twitter
	private boolean checkTwitterCredentials() {
		SharedPreferences prefs = getSharedPreferences("",MODE_PRIVATE);
		String twitterToken = prefs.getString(getResources().getString(R.string.pref_twitter_token),null);
		String twitterSecret = prefs.getString(getResources().getString(R.string.pref_twitter_secret),null);

		// Do we need to get credentials?
		if(twitterToken == null || twitterSecret == null) {
			// Build a configuration with generic credentials
			ConfigurationBuilder builder = new ConfigurationBuilder();
			builder.setOAuthConsumerKey(TWITTER_KEY);
			builder.setOAuthConsumerSecret(TWITTER_SECRET);
			twitter = new TwitterFactory(builder.build()).getInstance();

			Log.i(TAG,"need Twitter credentials");
			new GetTwitterCredentialsTask().execute();

			return false;
		}

		Log.i(TAG,"have Twitter credentials");

		// Build a configuration with these credentials
		ConfigurationBuilder builder = new ConfigurationBuilder();
		builder.setOAuthConsumerKey(TWITTER_KEY);
		builder.setOAuthConsumerSecret(TWITTER_SECRET);
		builder.setOAuthAccessToken(twitterToken);
		builder.setOAuthAccessTokenSecret(twitterSecret);
		twitter = new TwitterFactory(builder.build()).getInstance();

		return true;
	}

	// Process the Twitter crendentials we are getting back
	private void receiveTwitterCredentials(Uri uri) {
		// Build a configuration with generic credentials
		ConfigurationBuilder builder = new ConfigurationBuilder();
		builder.setOAuthConsumerKey(TWITTER_KEY);
		builder.setOAuthConsumerSecret(TWITTER_SECRET);
		twitter = new TwitterFactory(builder.build()).getInstance();

		try {
			// Use get() to complete the task synchronously
			new VerifyTwitterCredentialsTask().execute(uri).get();
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}
	}

	private MatOfRect findFaces(Mat image) {
		MatOfRect faces = new MatOfRect();

		double minFaceSize = Math.round(FACE_SCALE*image.width());

		faceDetector.detectMultiScale(image,faces,1.1,2,2,new Size(minFaceSize,minFaceSize),new Size());

		return faces;
	}
}
