package com.kevinriehm.mobileface;

import org.opencv.core.Mat;

public class FrameProcessor implements VisualView.FrameCallback {
	// VisualView.FrameCallback implementation

	public Mat onFrame(Mat frame) {
		return frame;
	}
}

