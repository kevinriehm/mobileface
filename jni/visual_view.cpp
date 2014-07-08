#include <ctime>
#include <fstream>
#include <streambuf>
#include <string>
#include <vector>

#include <pthread.h>

#include <android/bitmap.h>
#include <android/log.h>

#include <jni.h>

#include <opencv2/opencv.hpp>
#include <opencv2/contrib/detection_based_tracker.hpp>

#include <tracker/FaceTracker.hpp>

#define CAMERA_WIDTH  960
#define CAMERA_HEIGHT 540

#define TAG "MobileFace-VisualView"
#define LOGE(msg) __android_log_write(ANDROID_LOG_ERROR,TAG,msg)
#define LOGI(msg) __android_log_write(ANDROID_LOG_INFO,TAG,msg)

struct data_t {
	cv::VideoCapture *capture;
	cv::DetectionBasedTracker *tracker;

	FACETRACKER::FaceTracker *faceTracker;
	FACETRACKER::FaceTrackerParams *faceTrackerParams;

	cv::Rect face;
};

template <typename T>
std::string to_string(T val) {
	std::ostringstream os;
	os << val;
	return os.str();
}

void processFrame(JNIEnv *jenv, jobject jthis, data_t *data, uint8_t *pixels, AndroidBitmapInfo &info, cv::Mat &frame) {
	int trackResult;
	cv::Mat gray, output;
	cv::Point facecenter;
	cv::Size facesize, outsize;
	std::vector<cv::Rect> faces;
	FACETRACKER::PointVector faceshape;

	output = cv::Mat(frame.size(),CV_8UC4,pixels,info.stride);

	gray = cv::Mat(frame.size(),CV_8UC1);
	cv::cvtColor(frame,gray,CV_BGR2GRAY);

	data->tracker->process(gray);
	data->tracker->getObjects(faces);

	// Get the biggest detected object
	data->face = cv::Rect();
	for(unsigned int i = 0; i < faces.size(); i++)
		if(faces[i].width*faces[i].height > data->face.width*data->face.height)
			data->face = faces[i];

	// Indicate it with a rectangle
	if(data->face.width > 0)
		cv::rectangle(frame,data->face,cv::Scalar(0,0xFF,0),2);

	// Hand it off to the CI2CV SDK
	if(data->faceTracker) {
		trackResult = data->faceTracker->NewFrame(gray,data->faceTrackerParams);
LOGI(to_string(trackResult).c_str());
		// Draw the face if the tracking quality is good enough
		if(trackResult >= 1 || trackResult <= 10) {
			faceshape = data->faceTracker->getShape();
			for(unsigned int i = 0; i < faceshape.size(); i++)
				cv::circle(frame,faceshape[i],1,cv::Scalar(0,0,0xFF));
		} else data->faceTracker->Reset();
	}

	// Export the preview image
	cv::cvtColor(frame,output,CV_BGR2RGBA);
}

void set_source_dimensions(JNIEnv *jenv, jobject jthis, int width, int height) {
	jclass c_this;
	jmethodID method;

	c_this = jenv->GetObjectClass(jthis);
	method = jenv->GetMethodID(c_this,"setSourceDimensions","(II)V");

	jenv->CallVoidMethod(jthis,method,width,height);
}

extern "C" void Java_com_kevinriehm_mobileface_VisualView_00024VisualSurfaceView_processCameraFrame(JNIEnv *jenv, jobject jthis, jobject bitmap) {
	void *pixels;
	data_t *data;
	jclass c_this;
	cv::Mat frame;
	jobject o_data;
	AndroidBitmapInfo info;
	DetectionBasedTracker::Parameters trackerParams;
	jstring s_classifierpath, s_modelpath, s_paramspath;
	const char *classifierpath, *modelpath, *paramspath;
	jfieldID f_data, f_classifierpath, f_modelpath, f_paramspath;

	AndroidBitmap_getInfo(jenv,bitmap,&info);
	if(info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
		LOGE("invalid bitmap format");
		return;
	}

	if(AndroidBitmap_lockPixels(jenv,bitmap,&pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
		LOGE("failed to lock bitmap");
		return;
	}

	c_this = jenv->GetObjectClass(jthis);
	f_data = jenv->GetFieldID(c_this,"data","Ljava/nio/ByteBuffer;");
	o_data = jenv->GetObjectField(jthis,f_data);

	if(o_data) data = (data_t *) jenv->GetDirectBufferAddress(o_data);
	else {
		data = new data_t;

		data->capture = new cv::VideoCapture(CV_CAP_ANDROID_FRONT);
		if(!data->capture->isOpened()) {
			LOGE("failed to open camera");
			delete data;
			goto fail;
		}

		// Adjust the camera dimensions
		data->capture->set(CV_CAP_PROP_FRAME_WIDTH,CAMERA_WIDTH);
		data->capture->set(CV_CAP_PROP_FRAME_HEIGHT,CAMERA_HEIGHT);
		set_source_dimensions(jenv,jthis,CAMERA_WIDTH,CAMERA_HEIGHT);

		// Load the face classifier
		f_classifierpath = jenv->GetFieldID(c_this,"classifierPath","Ljava/lang/String;");
		s_classifierpath = (jstring) jenv->GetObjectField(jthis,f_classifierpath);

		classifierpath = jenv->GetStringUTFChars(s_classifierpath,NULL);
		data->tracker = new cv::DetectionBasedTracker(std::string(classifierpath),trackerParams);
		jenv->ReleaseStringUTFChars(s_classifierpath,classifierpath);

		data->tracker->run();

		// Load the CI2CV face tracker
		f_modelpath = jenv->GetFieldID(c_this,"modelPath","Ljava/lang/String;");
		s_modelpath = (jstring) jenv->GetObjectField(jthis,f_modelpath);
		modelpath = jenv->GetStringUTFChars(s_modelpath,NULL);
LOGI(modelpath);
		f_paramspath = jenv->GetFieldID(c_this,"paramsPath","Ljava/lang/String;");
		s_paramspath = (jstring) jenv->GetObjectField(jthis,f_paramspath);
		paramspath = jenv->GetStringUTFChars(s_paramspath,NULL);
LOGI(paramspath);
		data->faceTracker = FACETRACKER::LoadFaceTracker(modelpath);
		if(!data->faceTracker) LOGE("cannot load face tracker");

		data->faceTrackerParams = FACETRACKER::LoadFaceTrackerParams(paramspath);
		if(!data->faceTrackerParams) LOGE("cannot load face tracker parameters");

		jenv->ReleaseStringUTFChars(s_paramspath,paramspath);
		jenv->ReleaseStringUTFChars(s_modelpath,modelpath);

		// Save the data
		o_data = jenv->NewDirectByteBuffer(data,0);
		jenv->SetObjectField(jthis,f_data,o_data);
	}

	*data->capture >> frame;

	processFrame(jenv,jthis,data,(uint8_t *) pixels,info,frame);

fail:
	AndroidBitmap_unlockPixels(jenv,bitmap);
}

extern "C" void Java_com_kevinriehm_mobileface_VisualView_00024VisualSurfaceView_releaseCamera(JNIEnv *jenv, jobject jthis) {
	data_t *data;
	jclass c_this;
	jobject o_data;
	jfieldID f_data;

	c_this = jenv->GetObjectClass(jthis);
	f_data = jenv->GetFieldID(c_this,"data","Ljava/nio/ByteBuffer;");
	o_data = jenv->GetObjectField(jthis,f_data);

	if(o_data) {
		data = (data_t *) jenv->GetDirectBufferAddress(o_data);

		data->tracker->stop();
		data->capture->release();

		delete data->tracker;
		delete data->capture;
		delete data;

		jenv->SetObjectField(jthis,f_data,NULL);
	}
}

// Save the face from the current frame into filename;
// returns whether a face was actually saved
extern "C" jboolean Java_com_kevinriehm_mobileface_VisualView_00024VisualSurfaceView_saveFaceImage(JNIEnv *jenv, jobject jthis, jstring filename) {
	data_t *data;
	jclass c_this;
	jobject o_data;
	jfieldID f_data;
	cv::Mat face, frame;
	const char *filestr;

	c_this = jenv->GetObjectClass(jthis);
	f_data = jenv->GetFieldID(c_this,"data","Ljava/nio/ByteBuffer;");
	o_data = jenv->GetObjectField(jthis,f_data);

	if(o_data) {
		data = (data_t *) jenv->GetDirectBufferAddress(o_data);

		// Is there actually a face in this frame?
		if(data->face.width == 0)
			return false;

		// Save the image of the face
		filestr = jenv->GetStringUTFChars(filename,NULL);

		data->capture->retrieve(frame);
		face = cv::Mat(frame,data->face);
		imwrite(std::string(filestr),face);

		jenv->ReleaseStringUTFChars(filename,filestr);

		return true;
	}

	return false;
}

