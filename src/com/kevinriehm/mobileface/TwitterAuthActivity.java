package com.kevinriehm.mobileface;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class TwitterAuthActivity extends Activity {
	private final static String TAG = "MobileFace-TwitterAuthActivity";

	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.twitter_auth);

		WebView webview = (WebView) findViewById(R.id.twitter_auth_webview);

		if(webview == null) {
			Log.e(TAG,"cannot find WebView");
			return;
		}

		webview.setWebViewClient(new WebViewClient() {
			public void onPageStarted(WebView view, String url, Bitmap favicon) {
				shouldOverrideUrlLoading(view,url);
			}

			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				// Is this the callback we gave to Twitter?
				if(url.startsWith(getResources().getString(R.string.twitter_callback_url))) {
					Log.i(TAG,"finishing");

					Intent result = new Intent();
					result.setData(Uri.parse(url));
					setResult(RESULT_OK,result);

					finish();

					return true;
				}

				// Nope, carry on
				return false;
			}
		});

		WebSettings settings = webview.getSettings();
		settings.setSaveFormData(false);
		settings.setSavePassword(false);

		webview.loadUrl(getIntent().getDataString());
	}
}

