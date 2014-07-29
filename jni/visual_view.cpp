#include <cstdint>
#include <ctime>
#include <fstream>
#include <iostream>
#include <memory>
#include <streambuf>
#include <string>
#include <vector>

#include <pthread.h>
#include <signal.h>

#include <android/bitmap.h>
#include <android/log.h>

#include <jni.h>

#define _GLIBCXX_USE_NOEXCEPT noexcept
#include <jsoncons/json.hpp>

#include <opencv2/opencv.hpp>
#include <opencv2/contrib/detection_based_tracker.hpp>

#include <avatar/Avatar.hpp>
#include <tracker/FaceTracker.hpp>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
}

#define CAMERA_WIDTH  640
#define CAMERA_HEIGHT 480

#define MIN_FACE_STRENGTH 1

#define TAG "MobileFace-VisualView"
#define LOGE(msg) __android_log_write(ANDROID_LOG_ERROR,TAG,msg)
#define LOGI(msg) __android_log_write(ANDROID_LOG_INFO,TAG,msg)

enum {
	MODE_CAMERA,
	MODE_FILE
};

struct data_t {
	JavaVM *jvm;
	JNIEnv *jenv;

	jobject jthis;
	pthread_t worker;

	int mode;
	volatile bool enabled;

	jobject bitmap;

	volatile int orientation;
	cv::Mat orientedframe;
	cv::Mat orientedgray;

	bool sourceinited;

	int framecount;

	std::unique_ptr<cv::VideoCapture> capture;
	std::unique_ptr<cv::DetectionBasedTracker> tracker;

	std::string videopath;

	jsoncons::json expressions;

	AVFormatContext *avformat;
	AVStream *avstream;
	AVCodecContext *avcodec;

	AVPacket avpacket;
	int avpacketoffset;

	AVFrame *avframe;
	AVPicture avpicture;

	SwsContext *swscontext;

	std::unique_ptr<FACETRACKER::FaceTracker> facetracker;
	std::unique_ptr<FACETRACKER::FaceTrackerParams> facetrackerparams;

	int facestrength;
	bool calibrated;

	std::unique_ptr<AVATAR::Avatar> avatar;

	cv::Rect face;

	jmethodID m_blitBitmap;
};

template <typename T>
std::string to_string(T val) {
	std::ostringstream os;
	os << val;
	return os.str();
}

jint get_int(JNIEnv *jenv, jobject jthis, const char *name) {
	jclass c_this;
	jfieldID f_int;

	c_this = jenv->GetObjectClass(jthis);
	f_int = jenv->GetFieldID(c_this,name,"I");

	return jenv->GetIntField(jthis,f_int);
}

std::string get_string(JNIEnv *jenv, jobject jthis, const char *name) {
	jclass c_this;
	jstring s_string;
	jfieldID f_string;
	std::string string;
	const char *cstring;

	c_this = jenv->GetObjectClass(jthis);
	f_string = jenv->GetFieldID(c_this,name,"Ljava/lang/String;");
	s_string = (jstring) jenv->GetObjectField(jthis,f_string);
	if(!s_string) return std::string();

	cstring = jenv->GetStringUTFChars(s_string,NULL);
	string = std::string(cstring);
	jenv->ReleaseStringUTFChars(s_string,cstring);

	return string;
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

bool get_frame(data_t *data, cv::Mat &frame) {
	switch(data->mode) {
	case MODE_CAMERA:
		*data->capture >> frame;
		break;

	case MODE_FILE:
		// Get a frame
		int gotframe;
		do {
			// Demux a packet from the stream
			if(data->avpacketoffset >= data->avpacket.size) {
				data->avpacketoffset = 0;

				do {
					av_free_packet(&data->avpacket);

					if(av_read_frame(data->avformat,&data->avpacket) < 0)
						return false; // EOF
				} while(data->avpacket.stream_index != data->avstream->index);
			}

			AVPacket packet;
			av_init_packet(&packet);
			packet.data = data->avpacket.data + data->avpacketoffset;
			packet.size = data->avpacket.size - data->avpacketoffset;

			// Decode a frame from the packet, if we can
			int nbytes = avcodec_decode_video2(data->avcodec,data->avframe,&gotframe,&packet);
			if(nbytes < 0) {
				LOGE("cannot decode frame");
				return false;
			}
			data->avpacketoffset += nbytes;
		} while(!gotframe);

		// Make sure we have a destination
		if(!data->avpicture.data[0])
			avpicture_alloc(&data->avpicture,PIX_FMT_BGR24,data->avframe->width,data->avframe->height);

		// Transform from YUV to BGR
		data->swscontext = sws_getCachedContext(data->swscontext,data->avframe->width,data->avframe->height,
			(AVPixelFormat) data->avframe->format,data->avframe->width,data->avframe->height,
			(AVPixelFormat) PIX_FMT_BGR24,SWS_POINT,NULL,NULL,NULL);
		sws_scale(data->swscontext,data->avframe->data,data->avframe->linesize,0,data->avframe->height,
			data->avpicture.data,data->avpicture.linesize);

		// Export it to frame
		frame = cv::Mat(data->avframe->height,data->avframe->width,CV_8UC3,data->avpicture.data[0],
			data->avpicture.linesize[0]);
		break;
	}

	data->framecount++;

	return true;
}

void get_current_frame(data_t *data, cv::Mat &frame) {
	switch(data->mode) {
	case MODE_CAMERA:
		data->capture->retrieve(frame);
		break;

	case MODE_FILE:
		// Transform from YUV to BGR
		sws_scale(data->swscontext,data->avframe->data,data->avframe->linesize,0,data->avframe->height,
			data->avpicture.data,data->avpicture.linesize);

		// Export it to frame
		frame = cv::Mat(data->avframe->height,data->avframe->width,CV_8UC3,data->avpicture.data[0],
			data->avpicture.linesize[0]);
		break;
	}
}

void process_frame(data_t *data, cv::Mat &input, cv::Mat &output) {
	cv::Point facecenter;
	cv::Size facesize, outsize;
	std::vector<cv::Rect> faces;
	FACETRACKER::PointVector faceshape;
	std::vector<cv::Point3_<double> > faceshape3d;

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
		jsoncons::json expression;
		expression["frame"] = data->framecount;
		expression["has_face"] = false;

		data->facestrength = data->facetracker->NewFrame(data->orientedgray,data->facetrackerparams.get());

		// Outline and draw the avatar if the tracking quality is good enough
		if(data->facestrength >= MIN_FACE_STRENGTH) {
			faceshape = data->facetracker->getShape();
			faceshape3d = data->facetracker->get3DShape();

			if(!data->calibrated) {
				data->avatar->Initialise(data->orientedframe,faceshape);
				data->calibrated = true;
			}

			for(unsigned int i = 0; i < faceshape.size(); i++)
				cv::circle(data->orientedframe,faceshape[i],1,cv::Scalar(0,0,0xFF));

			data->avatar->Animate(data->orientedframe,data->orientedframe,faceshape);

			// If this is a video, save this frame's expression
			if(data->mode == MODE_FILE) {
				expression["has_face"] = true;

				jsoncons::json points2d(jsoncons::json::an_array);
				jsoncons::json points3d(jsoncons::json::an_array);

				for(unsigned int i = 0; i < faceshape3d.size(); i++) {
					jsoncons::json point2d(jsoncons::json::an_array);
					jsoncons::json point3d(jsoncons::json::an_array);

					point2d.add(faceshape[i].x);
					point2d.add(faceshape[i].y);
					points2d.add(std::move(point2d));

					point3d.add(faceshape3d[i].x);
					point3d.add(faceshape3d[i].y);
					point3d.add(faceshape3d[i].z);
					points3d.add(std::move(point3d));
				}

				expression["points2d"] = std::move(points2d);
				expression["points3d"] = std::move(points3d);
			}
		}

		// Have an entry for every frame
		if(data->mode == MODE_FILE) {
			data->expressions["frames"].add(std::move(expression));
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

	data->jenv->CallVoidMethod(data->jthis,data->m_blitBitmap,xmargin,ymargin,w,h);
}

bool init_source(data_t *data) {
	LOGI(std::string("initializing source, mode ").append(to_string(data->mode)).c_str());

	AVCodec *codec;

	switch(data->mode) {
	case MODE_CAMERA:
		data->capture = std::unique_ptr<cv::VideoCapture>(new cv::VideoCapture(CV_CAP_ANDROID_FRONT));
		if(!data->capture->isOpened()) {
			LOGE("cannot open camera");
			data->capture.reset();
			return false;
		}

		// Adjust the camera dimensions
		data->capture->set(CV_CAP_PROP_FRAME_WIDTH,CAMERA_WIDTH);
		data->capture->set(CV_CAP_PROP_FRAME_HEIGHT,CAMERA_HEIGHT);
		break;

	case MODE_FILE:
		// Prepare to record the facial expressions
		data->expressions["frames"] = std::move(jsoncons::json(jsoncons::json::an_array));

		av_register_all();

		// Open the video file
		data->avformat = avformat_alloc_context();
		if(avformat_open_input(&data->avformat,data->videopath.c_str(),NULL,NULL) < 0) {
			LOGE(std::string("cannot open ").append(data->videopath).c_str());
			return false;;
		}

		// Get info about the file
		if(avformat_find_stream_info(data->avformat,NULL) < 0) {
			LOGE("cannot get video file info");
			return false;
		}

		// Find the first video stream
		data->avstream = NULL;
		for(unsigned int i = 0; i < data->avformat->nb_streams; i++) {
			AVCodecContext *codec = data->avformat->streams[i]->codec;
			if(codec->codec_type == AVMEDIA_TYPE_VIDEO) {
				data->avstream = data->avformat->streams[i];
				break;
			}
		}

		// Abort if there isn't one
		if(!data->avstream) {
			LOGE("cannot find video stream");
			return false;
		}

		// Find a decoder for the stream
		codec = avcodec_find_decoder(data->avstream->codec->codec_id);
		if(!codec) {
			LOGE(std::string("cannot find decoder for codec ID ")
				.append(to_string(data->avstream->codec->codec_id)).c_str());
			return false;
		}

		data->avcodec = avcodec_alloc_context3(codec);
		data->avcodec->extradata = data->avstream->codec->extradata;
		data->avcodec->extradata_size = data->avstream->codec->extradata_size;

		if(avcodec_open2(data->avcodec,codec,NULL) < 0) {
			LOGE("cannot open codec");
			return false;
		}

		// Save some information about this file
		data->expressions["source"] = data->videopath;
		data->expressions["frame_width"] = data->avcodec->width;
		data->expressions["frame_height"] = data->avcodec->height;
		data->expressions["fps"] = av_q2d(data->avstream->avg_frame_rate);

		// Prepare for decoding
		av_init_packet(&data->avpacket);
		data->avpacketoffset = 0;

		data->avframe = av_frame_alloc();
		break;
	}

	data->framecount = 0;

	data->sourceinited = true;

	return true;
}

void uninit_source(data_t *data) {
	std::string expressionfile;

	if(!data->sourceinited)
		return;

	switch(data->mode) {
	case MODE_CAMERA:
		if(data->capture)
			data->capture->release();
		break;

	case MODE_FILE:
		// Save the expression data
		expressionfile = std::string(data->videopath.data(),data->videopath.find_last_of('.')).append(".expression.json");
		LOGI(std::string("Expression file: ").append(expressionfile).c_str());
		std::ofstream(expressionfile) << pretty_print(data->expressions) << std::endl;

		// Free everything in data
		if(data->swscontext) {
			sws_freeContext(data->swscontext);
			data->swscontext = NULL;
		}

		if(data->avpicture.data[0])
			avpicture_free(&data->avpicture);

		if(data->avframe)
			av_frame_free(&data->avframe);

		if(data->avpacket.data)
			av_free_packet(&data->avpacket);

		if(data->avcodec) {
			if(avcodec_is_open(data->avcodec))
				avcodec_close(data->avcodec);

			av_freep(&data->avcodec->subtitle_header);
			av_freep(&data->avcodec);
		}

		if(data->avformat)
			avformat_close_input(&data->avformat);
		break;
	}

	data->sourceinited = false;
}

void set_up_sigactions() {
	struct sigaction sa;

	// Handle the friendly termination signal
	sigemptyset(&sa.sa_mask);
	sa.sa_flags = 0;
	sa.sa_handler = [](int signum) {
		pthread_exit(NULL);
	};
	sigaction(SIGINT,&sa,NULL);
}

void cleanup_processing_thread(data_t *data) {
	uninit_source(data);

	LOGI("processing thread is dead");

	data->jvm->DetachCurrentThread();
}

void *processing_thread(data_t *data) {
	jclass c_this;
	double duration;
	cv::Mat input, output;
	struct timespec start, end;

	pthread_cleanup_push((void (*)(void *)) cleanup_processing_thread,(void *) data);

	set_up_sigactions();

	data->jvm->AttachCurrentThread(&data->jenv,NULL);

	LOGI("processing thread is alive");

	c_this = data->jenv->GetObjectClass(data->jthis);
	data->m_blitBitmap = data->jenv->GetMethodID(c_this,"blitBitmap","(IIII)V");

	if(!init_source(data)) {
		LOGE("cannot init video source");
		pthread_exit(NULL);
	}

	data->tracker->run();

	while(data->enabled) {
		clock_gettime(CLOCK_MONOTONIC,&start);

		if(!get_frame(data,input)) {
			LOGI("no more frames");
			pthread_exit(NULL);
		}

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

	data->mode = get_int(jenv,jthis,"mode");
	data->enabled = true;

	data->bitmap = jenv->NewGlobalRef(bitmap);
	data->orientation = 0;

	// Load the face classifier
	f_classifierpath = jenv->GetFieldID(c_this,"classifierPath","Ljava/lang/String;");
	s_classifierpath = (jstring) jenv->GetObjectField(jthis,f_classifierpath);

	classifierpath = jenv->GetStringUTFChars(s_classifierpath,NULL);
	data->tracker = std::unique_ptr<cv::DetectionBasedTracker>(new cv::DetectionBasedTracker(std::string(classifierpath),trackerParams));
	jenv->ReleaseStringUTFChars(s_classifierpath,classifierpath);

	// Load the CI2CV face tracker
	f_modelpath = jenv->GetFieldID(c_this,"modelPath","Ljava/lang/String;");
	s_modelpath = (jstring) jenv->GetObjectField(jthis,f_modelpath);
	modelpath = jenv->GetStringUTFChars(s_modelpath,NULL);

	f_paramspath = jenv->GetFieldID(c_this,"paramsPath","Ljava/lang/String;");
	s_paramspath = (jstring) jenv->GetObjectField(jthis,f_paramspath);
	paramspath = jenv->GetStringUTFChars(s_paramspath,NULL);

	data->facetracker = std::unique_ptr<FACETRACKER::FaceTracker>(FACETRACKER::LoadFaceTracker(modelpath));
	if(!data->facetracker) LOGE("cannot load face tracker");

	data->facetrackerparams = std::unique_ptr<FACETRACKER::FaceTrackerParams>(FACETRACKER::LoadFaceTrackerParams(paramspath));
	if(!data->facetrackerparams) LOGE("cannot load face tracker parameters");

	jenv->ReleaseStringUTFChars(s_paramspath,paramspath);
	jenv->ReleaseStringUTFChars(s_modelpath,modelpath);

	data->facestrength = 0;
	data->calibrated = false;

	// Load the CI2CV avatar
	f_avatarpath = jenv->GetFieldID(c_this,"avatarPath","Ljava/lang/String;");
	s_avatarpath = (jstring) jenv->GetObjectField(jthis,f_avatarpath);
	avatarpath = jenv->GetStringUTFChars(s_avatarpath,NULL);

	data->avatar = std::unique_ptr<AVATAR::Avatar>(AVATAR::LoadAvatar(avatarpath));
	if(!data->avatar) LOGE("cannot load avatar");
	data->avatar->setAvatar(2);

	jenv->ReleaseStringUTFChars(s_avatarpath,avatarpath);

	data->videopath = get_string(jenv,jthis,"videoPath");

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
		data->calibrated = false;
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

