package com.kevinriehm.mobileface;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class TwitterAuthActivity extends Activity {
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.twitter_auth);

		WebView webview = (WebView) findViewById(R.id.twitter_auth_webview);
		webview.setWebViewClient(new WebViewClient() {
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				// Is this the callback we gave to Twitter?
				if(url.startsWith(getResources().getString(R.string.twitter_callback_url))) {
					Intent result = new Intent();
					result.setData(Uri.parse(url));
					setResult(RESULT_OK,result);

					finish();

					return true;
				}

				// Nope; carry on
				return false;
			}
		});

		webview.loadUrl(getIntent().getDataString());
	}
}

