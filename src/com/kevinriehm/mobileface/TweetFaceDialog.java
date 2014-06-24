package com.kevinriehm.mobileface;

import java.io.File;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.InputFilter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;

import twitter4j.GeoLocation;
import twitter4j.Twitter;
import twitter4j.StatusUpdate;

public class TweetFaceDialog extends AlertDialog {
	private final static String TAG = "MobileFace-TweetFaceDialog";

	private final static int TWEET_MAX_LENGTH = 140;

	public TweetFaceDialog(Context context, final Twitter twitter, String tweet, final File face, final Location location) {
		super(context);

		Resources resources = context.getResources();

		// Set up the tweet preview
		LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View view = inflater.inflate(R.layout.tweet_preview,null);

		final EditText text = (EditText) view.findViewById(R.id.tweet_preview_text);
		text.setText(tweet);

		SharedPreferences prefs = context.getSharedPreferences("",Context.MODE_PRIVATE);
		int mediaChars = prefs.getInt(resources.getString(R.string.pref_twitter_media_chars),23);
		text.setFilters(new InputFilter[] {new InputFilter.LengthFilter(TWEET_MAX_LENGTH - mediaChars)});

		ImageView image = (ImageView) view.findViewById(R.id.tweet_preview_image);
		image.setImageURI(Uri.fromFile(face));

		setView(view);

		// Set up the other stuff
		setTitle(resources.getString(R.string.tweet_preview_title));

		setButton(BUTTON_POSITIVE,resources.getString(R.string.tweet_preview_positive),new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				new TweetTask().execute();
			}

			class TweetTask extends AsyncTask<Void, Void, Void> {
				protected Void doInBackground(Void... args) {
					try {
						// Tweet!
						StatusUpdate status = new StatusUpdate(text.getText().toString());
						status.setMedia(face);

						if(location == null) Log.i(TAG,"location is null");
						else {
							status.setDisplayCoordinates(true);
							status.setLocation(new GeoLocation(location.getLatitude(),location.getLongitude()));
						}

						twitter.updateStatus(status);
					} catch(Exception e) {
						Log.e(TAG,e.toString());
						e.printStackTrace();
					}

					// Clean-up after ourselves
					face.delete();

					return null;
				}
			}
		});

		setButton(BUTTON_NEGATIVE,resources.getString(R.string.tweet_preview_negative),new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
			}
		});

		setCanceledOnTouchOutside(false);
	}
}

