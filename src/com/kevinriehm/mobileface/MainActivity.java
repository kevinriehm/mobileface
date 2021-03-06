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
import android.database.Cursor;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Browser;
import android.provider.MediaStore;
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
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;

public class MainActivity extends Activity {
	private static final String TAG = "MobileFace-MainActivity";

	private static final int REQUEST_TWITTER_AUTH    = 0;
	private static final int REQUEST_VIDEO_FILE      = 1;
	private static final int REQUEST_EXPRESSION_FILE = 2;

	private VisualView visualView;

	private String faceFilePath;
	private String faceModelPath;
	private String faceParamsPath;
	private String avatarPath;

	private String twitterConsumerKey;
	private String twitterConsumerSecret;

	private Twitter twitter;
	private RequestToken requestToken;

	private String twitterToken = null;
	private String twitterSecret = null;

	private boolean leavingActivity;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.main);

		// Export some resources to actual files
		faceModelPath = copyResourceToFile(R.raw.face_mytracker_binary,"face.mytracker.binary").getAbsolutePath();
		faceParamsPath = copyResourceToFile(R.raw.face_mytrackerparams_binary,"face.mytrackerparams.binary").getAbsolutePath();
		avatarPath = copyResourceToFile(R.raw.ci2cv_avatar_binary,"ci2cv.avatar.binary").getAbsolutePath();

		// Set up the camera view
		visualView = (VisualView) findViewById(R.id.visual_view);

		// Un-obfuscate the Twitter credentials
		twitterConsumerKey = getObfuscatedData(R.raw.twitter_consumer_key);
		twitterConsumerSecret = getObfuscatedData(R.raw.twitter_consumer_secret);

		// We're not going anywhere, yet
		leavingActivity = false;
	}

	public void onPause() {
		super.onPause();

		visualView.disable();
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		Log.i(TAG,"activity result: " + (data == null ? data : data.toString()));

		switch(requestCode) {
			case REQUEST_TWITTER_AUTH:
				if(resultCode == RESULT_OK)
					receiveTwitterCredentials(data.getData());
				break;

			case REQUEST_VIDEO_FILE:
				if(resultCode == RESULT_OK) {
					String path = getFilePath(data.getData());

					Log.i(TAG,"Video file path: " + path);

					// Pass it on to the processor
					visualView.setVideoPath(path);
					visualView.setMode(VisualView.MODE_FILE);
					visualView.disable();
					visualView.enable();
				}
				break;

			case REQUEST_EXPRESSION_FILE:
				if(resultCode == RESULT_OK) {
					String path = getFilePath(data.getData());

					Log.i(TAG,"Expression file path: " + path);

					// Abort if this isn't an expression file
					if(!path.endsWith(".expression.json")) {
						Toast.makeText(this,R.string.not_expression_file,Toast.LENGTH_SHORT);
						break;
					}

					Intent intent = new Intent(this,ExpressionViewActivity.class);
					intent.setData(Uri.parse("file://" + path));
					intent.putExtra("com.kevinriehm.mobileface.AvatarPath",avatarPath);
					startActivity(intent);

					leavingActivity = true;
				}
				break;

			default: Log.e(TAG,"unhandled request code: " + requestCode); break;
		}
	}

	public void onResume() {
		super.onResume();

		// Don't do anything if we're just going to leave right immediately
		if(leavingActivity) {
			leavingActivity = false;
			return;
		}

		// Make sure we have access to Twitter
		checkTwitterCredentials();

		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_9,this,new BaseLoaderCallback(this) {
			public void onManagerConnected(int status) {
				if(status == LoaderCallbackInterface.SUCCESS) {
					// Activate the camera processing
					visualView.setModelPath(faceModelPath);
					visualView.setParamsPath(faceParamsPath);
					visualView.setAvatarPath(avatarPath);
					visualView.enable();
				} else super.onManagerConnected(status);
			}
		});
	}

	// Activity overrides

	public void onDestroy() {
		super.onDestroy();

		visualView.disable();
	}

	// UI callbacks

	public void tweetSelfie(View view) {
		// Try saving the tweet image
		File faceFile = null;
		try {
			faceFile = File.createTempFile("selfie",".jpg",getDir("temp",Context.MODE_PRIVATE));
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
			return;
		}

		if(!visualView.saveFaceImage(faceFile.getAbsolutePath())) { // Didn't find any faces?
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

		// Send it on its merry way
		new TweetFaceDialog(this,twitter,tweet,faceFile,location).show();
	}

	public void deauthenticate(View view) {
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

	public void openVideo(View view) {
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.setType("video/*");

		Intent chooser = Intent.createChooser(intent,"Open a Video File");
		startActivityForResult(chooser,REQUEST_VIDEO_FILE);
	}

	public void resetTracking(View view) {
		visualView.resetTracking();
	}

	public void calibrateExpression(View view) {
		visualView.calibrateExpression();
	}

	public void viewExpression(View view) {
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.setType("*/*");

		Intent chooser = Intent.createChooser(intent,"Open an Expression JSON File");
		startActivityForResult(chooser,REQUEST_EXPRESSION_FILE);
	}

	// Helper classes/functions

	// Copy the data in resid to filename
	private File copyResourceToFile(int resid, String filename) {
		File file = null;

		try {
			InputStream resStream = getResources().openRawResource(resid);

			file = new File(getDir("resources",Context.MODE_PRIVATE),filename);
			FileOutputStream fileStream = new FileOutputStream(file);

			try {
				int numBytes;
				byte[] buf = new byte[1024];
				while((numBytes = resStream.read(buf)) > 0)
					fileStream.write(buf,0,numBytes);
			} finally {
				resStream.close();
				fileStream.close();
			}
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}

		return file;
	}

	// De-obfuscate whatever data resid refers to
	private String getObfuscatedData(int resid) {
		String data = "";

		InputStream stream = getResources().openRawResource(resid);

		try {
			byte buf[] = new byte[2];
			while(stream.read(buf,0,2) == 2)
				data += (char) (buf[0] ^ buf[1]);
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}

		return data;
	}

	// Get the actual file name for uri
	private String getFilePath(Uri uri) {
		String path = null;

		if(uri.getScheme().equals("content")) {
			Cursor cursor = getContentResolver().query(uri,new String[] {MediaStore.MediaColumns.DATA},null,null,null);
			cursor.moveToFirst();
			path = cursor.getString(0);
		} else if(uri.getScheme().equals("file"))
			path = uri.getPath();
		else Log.e(TAG,"unknown URI scheme: " + uri.getScheme());

		return path;
	}

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
			builder.setOAuthConsumerKey(twitterConsumerKey);
			builder.setOAuthConsumerSecret(twitterConsumerSecret);
			twitter = new TwitterFactory(builder.build()).getInstance();

			Log.i(TAG,"need Twitter credentials");
			new GetTwitterCredentialsTask().execute();

			return false;
		}

		Log.i(TAG,"have Twitter credentials");

		// Build a configuration with these credentials
		ConfigurationBuilder builder = new ConfigurationBuilder();
		builder.setOAuthConsumerKey(twitterConsumerKey);
		builder.setOAuthConsumerSecret(twitterConsumerSecret);
		builder.setOAuthAccessToken(twitterToken);
		builder.setOAuthAccessTokenSecret(twitterSecret);
		twitter = new TwitterFactory(builder.build()).getInstance();

		return true;
	}

	// Process the Twitter crendentials we are getting back
	private void receiveTwitterCredentials(Uri uri) {
		// Build a configuration with generic credentials
		ConfigurationBuilder builder = new ConfigurationBuilder();
		builder.setOAuthConsumerKey(twitterConsumerKey);
		builder.setOAuthConsumerSecret(twitterConsumerSecret);
		twitter = new TwitterFactory(builder.build()).getInstance();

		try {
			// Use get() to complete the task synchronously
			new VerifyTwitterCredentialsTask().execute(uri).get();
		} catch(Exception e) {
			Log.e(TAG,e.toString());
			e.printStackTrace();
		}
	}
}

