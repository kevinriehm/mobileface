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

#include <avatar/Avatar.hpp>
#include <tracker/FaceTracker.hpp>

#define CAMERA_WIDTH  640
#define CAMERA_HEIGHT 480

#define MIN_FACE_STRENGTH 1

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
	pthread_t worker;

	enum mode mode;
	volatile bool enabled;

	jobject bitmap;

	volatile int orientation;
	cv::Mat orientedframe;
	cv::Mat orientedgray;

	cv::VideoCapture *capture;
	cv::DetectionBasedTracker *tracker;

	FACETRACKER::FaceTracker *facetracker;
	FACETRACKER::FaceTrackerParams *facetrackerparams;

	int facestrength;
	bool calibrated;

	AVATAR::Avatar *avatar;

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

void get_current_frame(data_t *data, cv::Mat &frame) {
	switch(data->mode) {
	case MODE_CAMERA:
		data->capture->retrieve(frame);
		break;
	}
}

void process_frame(data_t *data, cv::Mat &input, cv::Mat &output) {
	cv::Point facecenter;
	cv::Size facesize, outsize;
	std::vector<cv::Rect> faces;
	FACETRACKER::PointVector faceshape;

	output = cv::Mat(input.size(),CV_8UC4);

	// Make sure the matricies are ready
	if(data->orientation == 0 || data->orientation == 180) {
		if(input.rows != data->orientedframe.rows || input.cols != data->orientedframe.cols)
			data->orientedframe = cv::Mat(input.rows,input.cols,CV_8UC3);

		if(input.rows != data->orientedgray.rows || input.cols != data->orientedgray.cols)
			data->orientedgray = cv::Mat(input.rows,input.cols,CV_8UC1);

		if(input.rows != output.rows || input.cols != output.cols)
			output = cv::Mat(input.rows,input.cols,CV_8UC3);
	} else {
		if(input.rows != data->orientedframe.cols || input.cols != data->orientedframe.rows)
			data->orientedframe = cv::Mat(input.cols,input.rows,CV_8UC3);

		if(input.rows != data->orientedgray.cols || input.cols != data->orientedgray.rows)
			data->orientedgray = cv::Mat(input.cols,input.rows,CV_8UC1);

		if(input.rows != output.cols || input.cols != output.rows)
			output = cv::Mat(input.cols,input.rows,CV_8UC3);
	}

	// Orient the frame properly
	switch(data->orientation) {
	case 0:
		cv::flip(input,data->orientedframe,1);
		break;

	case 90:
		cv::transpose(input,data->orientedframe);
		break;

	case 180:
		cv::flip(input,data->orientedframe,0);
		break;

	case 270:
		cv::transpose(input,data->orientedframe);
		cv::flip(data->orientedframe,data->orientedframe,-1);
		break;
	}

	// Get a grayscale version
	cv::cvtColor(data->orientedframe,data->orientedgray,CV_BGR2GRAY);

	// Look for faces
	data->tracker->process(data->orientedgray);
	data->tracker->getObjects(faces);

	// Get the biggest detected object
	data->face = cv::Rect();
	for(unsigned int i = 0; i < faces.size(); i++)
		if(faces[i].width*faces[i].height > data->face.width*data->face.height)
			data->face = faces[i];

	// Indicate it with a rectangle
	if(data->face.width > 0)
		cv::rectangle(data->orientedframe,data->face,cv::Scalar(0,0xFF,0),2);

	// Hand it off to the CI2CV SDK
	if(data->facetracker) {
		data->facestrength = data->facetracker->NewFrame(data->orientedgray,data->facetrackerparams);

		// Outline and draw the avatar if the tracking quality is good enough
		if(data->facestrength >= MIN_FACE_STRENGTH) {
			faceshape = data->facetracker->getShape();

			for(unsigned int i = 0; i < faceshape.size(); i++)
				cv::circle(data->orientedframe,faceshape[i],1,cv::Scalar(0,0,0xFF));

			if(data->calibrated)
				data->avatar->Animate(data->orientedframe,data->orientedframe,faceshape);
		}
	}

	// Export the preview image
	cv::cvtColor(data->orientedframe,output,CV_BGR2RGBA);
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

	sigemptyset(&sa.sa_mask);
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

extern "C" void Java_com_kevinriehm_mobileface_VisualView_setViewOrientation(JNIEnv *jenv, jobject jthis, jint orientation) {
	data_t *data;

	if(data = get_data(jenv,jthis)) {
		LOGI(std::string("setting view orientation to ").append(to_string(orientation)).c_str());
		data->orientation = orientation;
	}
}

extern "C" void Java_com_kevinriehm_mobileface_VisualView_spawnWorker(JNIEnv *jenv, jobject jthis, jint mode, jobject bitmap) {
	data_t *data;
	jclass c_this;
	DetectionBasedTracker::Parameters trackerParams;
	const char *avatarpath, *classifierpath, *modelpath, *paramspath;
	jstring s_avatarpath, s_classifierpath, s_modelpath, s_paramspath;
	jfieldID f_avatarpath, f_classifierpath, f_modelpath, f_paramspath;

	// Is the worker already running?
	if(data = get_data(jenv,jthis)) return;

	c_this = jenv->GetObjectClass(jthis);

	data = new data_t();

	jenv->GetJavaVM(&data->jvm);
	data->jthis = jenv->NewGlobalRef(jthis);

	data->mode = MODE_CAMERA;
	data->enabled = true;

	data->bitmap = jenv->NewGlobalRef(bitmap);
	data->orientation = 0;

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

	data->facetracker = FACETRACKER::LoadFaceTracker(modelpath);
	if(!data->facetracker) LOGE("cannot load face tracker");

	data->facetrackerparams = FACETRACKER::LoadFaceTrackerParams(paramspath);
	if(!data->facetrackerparams) LOGE("cannot load face tracker parameters");

	jenv->ReleaseStringUTFChars(s_paramspath,paramspath);
	jenv->ReleaseStringUTFChars(s_modelpath,modelpath);

	data->facestrength = 0;
	data->calibrated = false;

	// Load the CI2CV avatar
	f_avatarpath = jenv->GetFieldID(c_this,"avatarPath","Ljava/lang/String;");
	s_avatarpath = (jstring) jenv->GetObjectField(jthis,f_avatarpath);
	avatarpath = jenv->GetStringUTFChars(s_avatarpath,NULL);

	data->avatar = AVATAR::LoadAvatar(avatarpath);
	if(!data->avatar) LOGE("cannot load avatar");
	data->avatar->setAvatar(2);

	jenv->ReleaseStringUTFChars(s_avatarpath,avatarpath);

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
	if(data->facetrackerparams) delete data->facetrackerparams;
	if(data->facetracker) delete data->facetracker;
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
		data->facetracker->Reset();
	}
}

extern "C" bool Java_com_kevinriehm_mobileface_VisualView_calibrateExpression(JNIEnv *jenv, jobject jthis) {
	data_t *data;
	cv::Mat frame;
	FACETRACKER::PointVector shape;

	if(data = get_data(jenv,jthis)) {
		LOGI("calibrating expression");

		if(data->facestrength < MIN_FACE_STRENGTH) {
			LOGE("cannot calibrate expression; face tracking too weak");
			return false;
		}

		get_current_frame(data,frame);
		shape = data->facetracker->getShape();

		data->avatar->Initialise(frame,shape);

		data->calibrated = true;

		return true;
	}

	return false;
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

		get_current_frame(data,frame);
		face = cv::Mat(frame,data->face);
		imwrite(std::string(filestr),face);

		jenv->ReleaseStringUTFChars(filename,filestr);

		return true;
	}

	return false;
}

