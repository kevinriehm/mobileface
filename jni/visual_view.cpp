#include <ctime>
#include <fstream>
#include <streambuf>
#include <string>
#include <vector>

#include <pthread.h>
#include <signal.h>

#include <android/bitmap.h>
#include <android/log.h>

#include <jni.h>

#include <opencv2/opencv.hpp>
#include <opencv2/contrib/detection_based_tracker.hpp>

#include <tracker/FaceTracker.hpp>

#define CAMERA_WIDTH  640
#define CAMERA_HEIGHT 480

#define TAG "MobileFace-VisualView"
#define LOGE(msg) __android_log_write(ANDROID_LOG_ERROR,TAG,msg)
#define LOGI(msg) __android_log_write(ANDROID_LOG_INFO,TAG,msg)

enum mode {
	MODE_CAMERA
};

struct data_t {
	JavaVM *jvm;
	JNIEnv *jenv;

	jobject jthis;

	volatile bool enabled;
	pthread_t worker;

	enum mode mode;

	jobject bitmap;

	cv::VideoCapture *capture;
	cv::DetectionBasedTracker *tracker;

	FACETRACKER::FaceTracker *faceTracker;
	FACETRACKER::FaceTrackerParams *faceTrackerParams;

	cv::Rect face;

	jmethodID m_blitBitmap;
};

template <typename T>
std::string to_string(T val) {
	std::ostringstream os;
	os << val;
	return os.str();
}

data_t *get_data(JNIEnv *jenv, jobject jthis) {
	jclass c_this;
	jobject o_data;
	jfieldID f_data;

	c_this = jenv->GetObjectClass(jthis);
	f_data = jenv->GetFieldID(c_this,"data","Ljava/nio/ByteBuffer;");
	o_data = jenv->GetObjectField(jthis,f_data);

	return o_data ? (data_t *) jenv->GetDirectBufferAddress(o_data) : NULL;
}

void set_data(JNIEnv *jenv, jobject jthis, data_t *data) {
	jclass c_this;
	jobject o_data;
	jfieldID f_data;

	c_this = jenv->GetObjectClass(jthis);
	f_data = jenv->GetFieldID(c_this,"data","Ljava/nio/ByteBuffer;");

	if(data) {
		o_data = jenv->NewDirectByteBuffer(data,0);
		jenv->SetObjectField(jthis,f_data,o_data);
	} else jenv->SetObjectField(jthis,f_data,NULL);
}

void get_frame(data_t *data, cv::Mat &frame) {
	switch(data->mode) {
	case MODE_CAMERA:
		*data->capture >> frame;
		break;
	}
}

void process_frame(data_t *data, cv::Mat &input, cv::Mat &output) {
	cv::Mat gray;
	int trackResult;
	cv::Point facecenter;
	cv::Size facesize, outsize;
	std::vector<cv::Rect> faces;
	FACETRACKER::PointVector faceshape;

	output = cv::Mat(input.size(),CV_8UC4);

	gray = cv::Mat(input.size(),CV_8UC1);
	cv::cvtColor(input,gray,CV_BGR2GRAY);

	data->tracker->process(gray);
	data->tracker->getObjects(faces);

	// Get the biggest detected object
	data->face = cv::Rect();
	for(unsigned int i = 0; i < faces.size(); i++)
		if(faces[i].width*faces[i].height > data->face.width*data->face.height)
			data->face = faces[i];

	// Indicate it with a rectangle
	if(data->face.width > 0)
		cv::rectangle(input,data->face,cv::Scalar(0,0xFF,0),2);

	// Hand it off to the CI2CV SDK
	if(data->faceTracker) {
		trackResult = data->faceTracker->NewFrame(gray,data->faceTrackerParams);

		// Draw the face if the tracking quality is good enough
		if(trackResult >= 1 || trackResult <= 10) {
			faceshape = data->faceTracker->getShape();
			for(unsigned int i = 0; i < faceshape.size(); i++)
				cv::circle(input,faceshape[i],1,cv::Scalar(0,0,0xFF));
		} else data->faceTracker->Reset();
	}

	// Export the preview image
	cv::cvtColor(input,output,CV_BGR2RGBA);
}

void draw_frame(data_t *data, cv::Mat &frame) {
LOGI("draw_frame()");
	void *pixels;
	cv::Mat bitmapmat;
	AndroidBitmapInfo info;
	int w, h, xmargin, ymargin;

	AndroidBitmap_getInfo(data->jenv,data->bitmap,&info);

	if(info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
		LOGE("bitmap is not RGBA8888");
		return;
	}

	// Fit frame to bitmap
	if(frame.cols*info.height > info.width*frame.rows) { // frame is wider
		w = info.width;
		h = frame.rows*info.width/frame.cols;
	} else { // bitmap is wider
		w = frame.cols*info.height/frame.rows;
		h = info.height;
	}

	xmargin = (info.width - w)/2;
	ymargin = (info.height - h)/2;

	AndroidBitmap_lockPixels(data->jenv,data->bitmap,&pixels);

	bitmapmat = cv::Mat(h,w,CV_8UC4,(uint8_t *) pixels + ymargin*info.stride + xmargin*4,info.stride);
	cv::resize(frame,bitmapmat,bitmapmat.size(),0,0,cv::INTER_NEAREST);

	AndroidBitmap_unlockPixels(data->jenv,data->bitmap);

	data->jenv->CallVoidMethod(data->jthis,data->m_blitBitmap);
}

void init_source(data_t *data) {
	switch(data->mode) {
	case MODE_CAMERA:
		data->capture = new cv::VideoCapture(CV_CAP_ANDROID_FRONT);
		if(!data->capture->isOpened()) {
			LOGE("failed to open camera");
			delete data->capture;
		}

		// Adjust the camera dimensions
		data->capture->set(CV_CAP_PROP_FRAME_WIDTH,CAMERA_WIDTH);
		data->capture->set(CV_CAP_PROP_FRAME_HEIGHT,CAMERA_HEIGHT);
		break;
	}
}

void uninit_source(data_t *data) {
	switch(data->mode) {
	case MODE_CAMERA:
		if(data->capture) {
			data->capture->release();
			delete data->capture;
		}
		break;
	}
}

void cleanup_processing_thread(data_t *data) {
	uninit_source(data);

	LOGI("processing thread is dead");

	data->jvm->DetachCurrentThread();
}

void exit_processing_thread(int signum) {
	pthread_exit(NULL);
}

void *processing_thread(data_t *data) {
	jclass c_this;
	double duration;
	struct sigaction sa;
	cv::Mat input, output;
	struct timespec start, end;

	pthread_cleanup_push((void (*)(void *)) cleanup_processing_thread,(void *) data);

	sa.sa_mask = 0;
	sa.sa_flags = 0;
	sa.sa_handler = exit_processing_thread;
	sigaction(SIGINT,&sa,NULL);

	data->jvm->AttachCurrentThread(&data->jenv,NULL);

	LOGI("processing thread is alive");

	c_this = data->jenv->GetObjectClass(data->jthis);
	data->m_blitBitmap = data->jenv->GetMethodID(c_this,"blitBitmap","()V");

	init_source(data);

	data->tracker->run();

	while(true) {
		clock_gettime(CLOCK_MONOTONIC,&start);

		get_frame(data,input);
		process_frame(data,input,output);
		draw_frame(data,output);

		clock_gettime(CLOCK_MONOTONIC,&end);
		duration = end.tv_sec - start.tv_sec
			+ (end.tv_nsec - start.tv_nsec)/1e9;
		LOGI(std::string("Frame time: ").append(to_string(duration)).c_str());
	}

	pthread_cleanup_pop(true);

	return NULL;
}

extern "C" void Java_com_kevinriehm_mobileface_VisualView_spawnWorker(JNIEnv *jenv, jobject jthis, jint mode, jobject bitmap) {
	data_t *data;
	jclass c_this;
	DetectionBasedTracker::Parameters trackerParams;
	jstring s_classifierpath, s_modelpath, s_paramspath;
	const char *classifierpath, *modelpath, *paramspath;
	jfieldID f_classifierpath, f_modelpath, f_paramspath;

	// Is the worker already running?
	if(data = get_data(jenv,jthis)) return;

	c_this = jenv->GetObjectClass(jthis);

	data = new data_t();

	jenv->GetJavaVM(&data->jvm);
	data->jthis = jenv->NewGlobalRef(jthis);
	data->enabled = true;
	data->bitmap = jenv->NewGlobalRef(bitmap);

	// Load the face classifier
	f_classifierpath = jenv->GetFieldID(c_this,"classifierPath","Ljava/lang/String;");
	s_classifierpath = (jstring) jenv->GetObjectField(jthis,f_classifierpath);

	classifierpath = jenv->GetStringUTFChars(s_classifierpath,NULL);
	data->tracker = new cv::DetectionBasedTracker(std::string(classifierpath),trackerParams);
	jenv->ReleaseStringUTFChars(s_classifierpath,classifierpath);

	// Load the CI2CV face tracker
	f_modelpath = jenv->GetFieldID(c_this,"modelPath","Ljava/lang/String;");
	s_modelpath = (jstring) jenv->GetObjectField(jthis,f_modelpath);
	modelpath = jenv->GetStringUTFChars(s_modelpath,NULL);

	f_paramspath = jenv->GetFieldID(c_this,"paramsPath","Ljava/lang/String;");
	s_paramspath = (jstring) jenv->GetObjectField(jthis,f_paramspath);
	paramspath = jenv->GetStringUTFChars(s_paramspath,NULL);

	data->faceTracker = FACETRACKER::LoadFaceTracker(modelpath);
	if(!data->faceTracker) LOGE("cannot load face tracker");

	data->faceTrackerParams = FACETRACKER::LoadFaceTrackerParams(paramspath);
	if(!data->faceTrackerParams) LOGE("cannot load face tracker parameters");

	jenv->ReleaseStringUTFChars(s_paramspath,paramspath);
	jenv->ReleaseStringUTFChars(s_modelpath,modelpath);

	// Store the data
	set_data(jenv,jthis,data);

	// Spawn the worker thread
	pthread_create(&data->worker,NULL,(void *(*)(void *)) processing_thread,(void *) data);
}

extern "C" void Java_com_kevinriehm_mobileface_VisualView_terminateWorker(JNIEnv *jenv, jobject jthis) {
	data_t *data;

	// Is there even a worker to terminate?
	if(data = get_data(jenv,jthis), !data) return;

	// Terminate it
	data->enabled = false;
	pthread_kill(data->worker,SIGINT);
	pthread_join(data->worker,NULL);

	// Clean up data
	if(data->faceTrackerParams) delete data->faceTrackerParams;
	if(data->faceTracker) delete data->faceTracker;
	if(data->tracker) delete data->tracker;

	jenv->DeleteGlobalRef(data->bitmap);
	jenv->DeleteGlobalRef(data->jthis);

	delete data;

	set_data(jenv,jthis,NULL);
}

extern "C" void Java_com_kevinriehm_mobileface_VisualView_resetTracking(JNIEnv *jenv, jobject jthis) {
	data_t *data;

	if(data = get_data(jenv,jthis)) {
		LOGI("resetting face tracking");
		data->faceTracker->Reset();
	}
}

// Save the face from the current frame into filename;
// returns whether a face was actually saved
extern "C" jboolean Java_com_kevinriehm_mobileface_VisualView_saveFaceImage(JNIEnv *jenv, jobject jthis, jstring filename) {
	data_t *data;
	cv::Mat face, frame;
	const char *filestr;

	if(data = get_data(jenv,jthis)) {
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

